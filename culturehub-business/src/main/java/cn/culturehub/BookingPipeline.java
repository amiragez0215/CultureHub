package cn.culturehub;

import cn.culturehub.v3.BookingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
@Profile("v3-p2")
@EnableScheduling
class BookingTopology {
  @Value("${v3.participant:CORE}") private String participant;
  @Value("${v3.booking.request-queue:booking.request.worker}") private String requestQueue;
  @Value("${v3.booking.inventory-queue:waitlist.promotion}") private String inventoryQueue;
  @Bean
  Declarables asyncBookingTopology() {
    var ingress = new DirectExchange(BookingMessage.INGRESS);
    var domain = new TopicExchange(BookingMessage.DOMAIN);
    var dead = new DirectExchange(BookingMessage.DEAD_EXCHANGE);
    var requests = queue(requestQueue);
    var inventory = queue(inventoryQueue);
    var deadRequests = QueueBuilder.durable(requestQueue + ".dead").quorum().build();
    var deadInventory = QueueBuilder.durable(inventoryQueue + ".dead").quorum().build();
    return new Declarables(ingress, domain, dead, requests, inventory, deadRequests, deadInventory,
        BindingBuilder.bind(requests).to(ingress).with(BookingMessage.COMMAND + "." + participant),
        BindingBuilder.bind(inventory).to(domain).with(BookingMessage.SETTLE + "." + participant),
        BindingBuilder.bind(inventory).to(domain).with(BookingMessage.SEAT_RELEASED + "." + participant),
        BindingBuilder.bind(deadRequests).to(dead).with(requests.getName()),
        BindingBuilder.bind(deadInventory).to(dead).with(inventory.getName()));
  }

  private Queue queue(String name) {
    return QueueBuilder.durable(name).quorum().withArgument("x-delivery-limit", 10)
        .deadLetterExchange(BookingMessage.DEAD_EXCHANGE).deadLetterRoutingKey(name).build();
  }
}

@Component
@Profile("v3-p2")
class BookingPipeline {
  private static final Logger LOG = LoggerFactory.getLogger(BookingPipeline.class);
  private static final String ATTEMPT = "x-booking-attempt";
  private final BookingLedger ledger;
  private final BookingRedis redis;
  private final BookingWorker worker;
  private final AsyncBooking booking;
  private final RabbitTransport transport;
  private final ObjectMapper json;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private CombinationBooking combinations;
  private final String consumer = UUID.randomUUID().toString();
  private final long reclaimMillis;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private io.micrometer.tracing.Tracer tracer;
  private long relayCursor;
  private long recoveryCursor;
  private long reconciliationCursor;
  @Value("${v3.participant:CORE}") private String participant;
  @Value("${v3.booking.request-queue:booking.request.worker}") private String requestQueue;

  BookingPipeline(BookingLedger ledger, BookingRedis redis, BookingWorker worker, AsyncBooking booking,
                  RabbitTransport transport, ObjectMapper json,
                  @Value("${v3.booking.reclaim-millis:5000}") long reclaimMillis) {
    if (reclaimMillis < 1000) throw new IllegalArgumentException("Invalid Stream reclaim lease");
    this.ledger = ledger;
    this.redis = redis;
    this.worker = worker;
    this.booking = booking;
    this.transport = transport;
    this.json = json;
    this.reclaimMillis = reclaimMillis;
  }

  @Scheduled(fixedDelayString = "${v3.booking.relay-delay-ms:300}")
  public void relay() {
    if (combinations != null) {
      try {
        for (var receipt : redis.receiveCombinations(consumer, reclaimMillis)) {
          var message = BookingMessage.of(receipt.request(), BookingMessage.COMBINATION, 1,
              receipt.request(), receipt.trace(), null, null);
          publishCommand(message);
          redis.ackCombination(receipt.stream());
        }
      } catch (Exception deferred) {
        if (deferred instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          return;
        }
        LOG.debug("Combination stream relay deferred", deferred);
      }
    }
    var sessions = sessionsAfter(relayCursor);
    if (sessions.isEmpty()) relayCursor = 0;
    for (long session : sessions) {
      relayCursor = session;
      try {
        for (var receipt : redis.receive(session, consumer, reclaimMillis)) {
          var message = BookingMessage.of(receipt.request(), BookingMessage.COMMAND, session,
              receipt.request(), receipt.trace(), receipt.stream(), receipt.epoch());
          publishCommand(message);
          redis.ack(session, receipt.stream());
        }
      } catch (Exception failure) {
        if (failure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          return;
        }
        LOG.debug("Booking Stream session {} retained unconfirmed entries: {}", session,
            failure.getClass().getSimpleName());
      }
    }
  }

  private void publishCommand(BookingMessage message) throws Exception {
    var properties = new MessageProperties();
    properties.setMessageId(message.messageId());
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    transport.send(BookingMessage.INGRESS, BookingMessage.COMMAND + "." + participant,
        new Message(json.writeValueAsString(message).getBytes(StandardCharsets.UTF_8), properties));
  }

  @Scheduled(fixedDelayString = "${v3.booking.recovery-delay-ms:1000}")
  @XxlJob("bookingRequestRecovery")
  public void recover() {
    var rows = ledger.store().db.queryForList("""
        select * from booking_request where id>? and operation in ('DIRECT','JOIN','CONFIRM','PROMOTE') and
          (state='RECEIVED' or (state in ('QUEUED','PROCESSING') and expires_at<=current_timestamp(6)))
        order by id limit 100
        """, recoveryCursor);
    if (rows.isEmpty()) recoveryCursor = 0;
    for (var row : rows) {
      var request = BookingLedger.request(row);
      recoveryCursor = request.sequence();
      try {
        if ("RECEIVED".equals(request.state())) booking.enqueue(request);
        else ledger.expire(request);
      } catch (RuntimeException failure) {
        LOG.debug("Booking recovery deferred for {}: {}", request.id(), failure.getClass().getSimpleName());
      }
    }
  }

  @Scheduled(fixedDelayString = "${v3.booking.reconciliation-delay-ms:30000}")
  @XxlJob("bookingInventoryReconciliation")
  public void reconcile() {
    var sessions = sessionsAfter(reconciliationCursor);
    if (sessions.isEmpty()) reconciliationCursor = 0;
    for (long session : sessions) {
      reconciliationCursor = session;
      try {
        var snapshot = ledger.snapshot(session, null);
        if (snapshot.paused()) continue;
        redis.apply(snapshot, null, false);
        for (var row : ledger.store().db.queryForList("""
            select request_id,stream_id,redis_epoch,operation,trace_id from booking_request
            where session_id=? and state='QUEUED' and operation in ('DIRECT','JOIN','CONFIRM','PROMOTE') order by id limit 100
            """, session)) {
          var receipt = new BookingRedis.Receipt((String) row.get("stream_id"), (String) row.get("request_id"),
              (String) row.get("redis_epoch"), (String) row.get("trace_id"));
          if (!redis.contains(receipt, session, BookingLedger.Operation.valueOf(row.get("operation").toString()).usesHold())) {
            var request = ledger.get(receipt.request(), false);
            if (request.pending()) {
              ledger.pauseIfStillPending(request, snapshot.epoch());
              LOG.warn("Booking receipt needs review: session={}, request={}", session, request.id());
            }
          }
        }
        for (String request : ledger.store().db.queryForList("""
            select request_id from booking_request
            where session_id=? and inventory_synced=false and finished_at is not null order by id limit 100
            """, String.class, session)) {
          var repaired = redis.apply(snapshot, request, false);
          var auditId = UUID.nameUUIDFromBytes(("reconcile:" + request).getBytes(StandardCharsets.UTF_8));
          ledger.audit(snapshot, auditId.toString(), request, "TERMINAL_RECEIPT_REPAIR", repaired.before(), repaired.after());
          ledger.inventorySynced(request);
        }
        redis.indexWaitlist(snapshot, ledger.waitlistHead(snapshot));
      } catch (RuntimeException failure) {
        var snapshot = ledger.snapshot(session, null);
        ledger.pauseForInventoryLoss(session, snapshot.epoch());
        LOG.warn("Booking inventory needs reconciliation for session {}: {}", session, failure.getClass().getSimpleName());
        continue;
      }
      try {
        var promotion = worker.planPromotion(session, null);
        if (promotion != null) booking.enqueue(promotion);
      } catch (Problem deferred) {
        if (deferred.status != 503) throw deferred;
      }
    }
  }

  private List<Long> sessionsAfter(long cursor) {
    return ledger.store().db.queryForList("""
        select b.session_id from booking_session b join activity_session x on x.id=b.session_id
        where b.redis_ready=true and b.paused=false and b.session_id>? and x.end_at>current_timestamp(6)
        order by b.session_id limit 100
        """, Long.class, cursor);
  }

  @RabbitListener(queues = {"${v3.booking.request-queue:booking.request.worker}",
      "${v3.booking.inventory-queue:waitlist.promotion}"})
  public void consume(Message delivery, Channel channel) throws Exception {
    String parent=null;
    try {parent=json.readTree(delivery.getBody()).path("traceId").asText();} catch(Exception malformed) { /* Original handler owns poison routing. */ }
    var span=cn.culturehub.v3.TraceCarrier.start(tracer,parent,"culturehub.booking.consume");
    try(var scope=span==null?null:tracer.withSpan(span)) {
      consumeDelivery(delivery,channel);
    }catch(Exception error){if(span!=null)span.error(error);throw error;}
    finally{if(span!=null)span.end();}
  }

  private void consumeDelivery(Message delivery, Channel channel) throws Exception {
    long tag = delivery.getMessageProperties().getDeliveryTag();
    String queue = delivery.getMessageProperties().getConsumerQueue();
    int attempt = 0;
    try {
      Object previous = delivery.getMessageProperties().getHeader(ATTEMPT);
      if (previous != null) {
        if (!(previous instanceof Number n) || n.intValue() < 0 || n.intValue() > 3
            || n.doubleValue() != n.intValue()) throw new IllegalArgumentException("Invalid retry attempt");
        attempt = ((Number) previous).intValue();
      }
      if (attempt > 0) Thread.sleep(1000);
      BookingMessage message = json.readValue(delivery.getBody(), BookingMessage.class);
      boolean group = BookingMessage.COMBINATION.equals(message.messageType());
      boolean command = group || BookingMessage.COMMAND.equals(message.messageType());
      if (!message.valid() || requestQueue.equals(queue) != command || (group && combinations == null)) {
        throw new IllegalArgumentException("Invalid booking message");
      }
      if (group) combinations.execute(message.requestId());
      else if (command) command(message);
      else inventory(message);
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        channel.abort();
        return;
      }
      boolean dead = attempt >= 3 || failure instanceof IllegalArgumentException
          || failure instanceof com.fasterxml.jackson.core.JsonProcessingException;
      var copy = MessageBuilder.fromClonedMessage(delivery).setHeader(ATTEMPT, attempt + 1).build();
      try {
        transport.send(dead ? BookingMessage.DEAD_EXCHANGE : "", queue, copy);
      } catch (Exception retryFailure) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        channel.abort();
        return;
      }
    }
    channel.basicAck(tag, false);
  }

  private void command(BookingMessage message) {
    var request = ledger.get(message.requestId(), false);
    if (request == null || request.session() != message.sessionId()) {
      throw new IllegalArgumentException("Unknown booking request");
    }
    if (!request.pending()) return;
    var receipt = new BookingRedis.Receipt(message.streamId(), message.requestId(), message.redisEpoch(), message.traceId());
    if (!redis.contains(receipt, message.sessionId(), request.operation().usesHold())) {
      if (!ledger.get(request.id(), false).pending()) return;
      throw new IllegalStateException("Missing booking Stream receipt");
    }
    try {
      worker.execute(message);
    } catch (Problem rejected) {
      if (rejected.status >= 500) throw rejected;
      ledger.reject(request, rejected.code);
    }
  }

  private void inventory(BookingMessage message) {
    var snapshot = ledger.snapshot(message.sessionId(), null);
    if (snapshot.paused()) throw new IllegalStateException("Booking reconciliation in progress");
    var inventory = redis.apply(snapshot, message.requestId(), false);
    ledger.markReady(snapshot);
    ledger.audit(snapshot, message.messageId(), message.requestId(), message.messageType(), inventory.before(), inventory.after());
    ledger.inventorySynced(message.requestId());
    redis.indexWaitlist(snapshot, ledger.waitlistHead(snapshot));
    var promotion = worker.planPromotion(message.sessionId(), message.traceId());
    if (promotion != null) booking.enqueue(promotion);
  }
}
