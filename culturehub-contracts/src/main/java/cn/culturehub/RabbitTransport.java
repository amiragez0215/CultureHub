package cn.culturehub;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Broker acceptance plus routing confirmation, never an end-to-end exactly-once guarantee. */
@Component
public class RabbitTransport {
  final RabbitTemplate template;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private io.micrometer.tracing.Tracer tracer;

  public RabbitTransport(RabbitTemplate template) {
    this.template = template;
    template.setMandatory(true);
  }

  public void send(String exchange, String route, Message message) throws Exception {
    String parent=null;
    try {parent=new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.getBody()).path("traceId").asText();}
    catch(Exception invalid){ /* Transport retains its original payload and routing semantics. */ }
    var span=cn.culturehub.v3.TraceCarrier.start(tracer,parent,"culturehub.rabbit.publish");
    try(var scope=span==null?null:tracer.withSpan(span)) {
      sendConfirmed(exchange,route,message);
    }catch(Exception error){if(span!=null)span.error(error);throw error;}
    finally{if(span!=null)span.end();}
  }

  private void sendConfirmed(String exchange,String route,Message message)throws Exception {
    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    var correlation = new CorrelationData(UUID.randomUUID().toString());
    template.send(exchange, route, message, correlation);
    var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
    // Spring AMQP populates returned before completing the confirm future.
    var returned = correlation.getReturned();
    if (returned != null)
      throw new IllegalStateException(
          "Rabbit Return "
              + returned.getReplyCode()
              + " "
              + returned.getReplyText()
              + " route="
              + returned.getRoutingKey());
    if (!confirm.isAck()) throw new IllegalStateException("Rabbit nack: " + confirm.getReason());
  }
}
