package cn.culturehub;

import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RabbitNotificationListener {
  final NotificationConsumer consumer;
  final RabbitTransport transport;

  public RabbitNotificationListener(NotificationConsumer consumer, RabbitTransport transport) {
    this.consumer = consumer;
    this.transport = transport;
  }

  @RabbitListener(
      id = "notifications",
      queues = {RabbitTopology.QUEUE, RabbitTopology.RETRY})
  public void receive(Message message, Channel channel) throws Exception {
    long tag = message.getMessageProperties().getDeliveryTag();
    int attempt = 0;
    try {
      Object value = message.getMessageProperties().getHeaders().get("x-retry-attempt");
      if (value != null) {
        if (!(value instanceof Number n) || n.intValue() < 0 || n.intValue() > 3)
          throw new IllegalArgumentException("Invalid retry header");
        attempt = ((Number) value).intValue();
      }
      // Durable retry queue with worker-side delay: no lossy TTL/DLX transfer or delay plugin.
      // A crash while waiting leaves the original delivery unacked for broker redelivery.
      if (attempt > 0) Thread.sleep(1000);
      consumer.consume(new String(message.getBody(), StandardCharsets.UTF_8));
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        channel.abort();
        throw failure;
      }
      boolean poison =
          failure instanceof IllegalArgumentException
              || failure instanceof com.fasterxml.jackson.core.JsonProcessingException;
      var props =
          MessagePropertiesBuilder.fromClonedProperties(message.getMessageProperties()).build();
      props.setHeader("x-original-queue", RabbitTopology.QUEUE);
      props.setHeader("x-failure-type", failure.getClass().getSimpleName());
      String route = poison || attempt >= 3 ? "dead" : "retry";
      props.setHeader("x-retry-attempt", route.equals("retry") ? attempt + 1 : attempt);
      try {
        transport.send(RabbitTopology.RECOVERY, route, new Message(message.getBody(), props));
      } catch (Exception publishFailure) {
        // Never ack an unconfirmed recovery copy. Closing the channel restores the unacked
        // original; recovery interval prevents an immediate requeue spin while broker is ill.
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally {
          channel.abort();
        }
        throw publishFailure;
      }
    }
    // consume()'s TransactionTemplate has returned after commit, or the recovery copy was
    // confirmed.
    channel.basicAck(tag, false);
  }
}
