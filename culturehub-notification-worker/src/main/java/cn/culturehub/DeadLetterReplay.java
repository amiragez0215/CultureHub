package cn.culturehub;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit bounded operator action. Fix the cause first; never changes event_id. */
@Component
@ConditionalOnProperty(name = "culture.replay-dlt", havingValue = "true")
public class DeadLetterReplay implements CommandLineRunner {
  final RabbitTemplate template;
  final RabbitTransport transport;

  public DeadLetterReplay(RabbitTemplate template, RabbitTransport transport) {
    this.template = template;
    this.transport = transport;
  }

  public void run(String... args) {
    for (int i = 0; i < 100; i++) {
      Boolean found =
          template.execute(
              channel -> {
                var delivery = channel.basicGet(RabbitTopology.DLQ, false);
                if (delivery == null) return false;
                try {
                  var props =
                      new DefaultMessagePropertiesConverter()
                          .toMessageProperties(
                              delivery.getProps(), delivery.getEnvelope(), "UTF-8");
                  props.setHeader("x-retry-attempt", 0);
                  props.setHeader("x-manual-replay", true);
                  // Generic routing is deliberately limited to the existing notification binding.
                  transport.send(
                      RabbitTopology.EXCHANGE,
                      "notification.replay",
                      new Message(delivery.getBody(), props));
                  channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                  return true;
                } catch (Exception error) {
                  channel.abort();
                  throw new IllegalStateException("DLQ replay retained", error);
                }
              });
      if (!Boolean.TRUE.equals(found)) break;
    }
  }
}
