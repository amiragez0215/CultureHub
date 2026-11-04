package cn.culturehub;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration
public class RabbitTopology {
  public static final String EXCHANGE = Event.TOPIC;
  public static final String QUEUE = "culturehub.notifications.v1";
  public static final String RECOVERY = "culturehub.notifications.recovery.v1";
  public static final String RETRY = QUEUE + ".retry";
  public static final String DLQ = Event.DLT;

  @Bean
  Declarables notificationTopology() {
    var exchange = new TopicExchange(EXCHANGE, true, false);
    var recovery = new DirectExchange(RECOVERY, true, false);
    var queue = QueueBuilder.durable(QUEUE).quorum().build();
    var retry = QueueBuilder.durable(RETRY).quorum().build();
    var dead = QueueBuilder.durable(DLQ).quorum().build();
    return new Declarables(
        exchange,
        recovery,
        queue,
        retry,
        dead,
        BindingBuilder.bind(queue).to(exchange).with("notification.*"),
        BindingBuilder.bind(retry).to(recovery).with("retry"),
        BindingBuilder.bind(dead).to(recovery).with("dead"));
  }

  public static String route(String eventType) {
    return "notification." + eventType;
  }
}
