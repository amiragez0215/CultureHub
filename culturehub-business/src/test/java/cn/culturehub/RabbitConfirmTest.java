package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitConfirmTest {
  @Test
  void returnOverridesPositiveConfirm() throws Exception {
    RabbitTemplate template = mock(RabbitTemplate.class);
    Message message = new Message("{}".getBytes(), new MessageProperties());
    doAnswer(
            i -> {
              CorrelationData c = i.getArgument(3);
              c.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "exchange", "missing"));
              c.getFuture().complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(template)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    assertThrows(
        IllegalStateException.class,
        () -> new RabbitTransport(template).send("exchange", "missing", message));
    verify(template).setMandatory(true);
  }

  @Test
  void positiveConfirmAcceptsPersistentMessage() throws Exception {
    RabbitTemplate template = mock(RabbitTemplate.class);
    Message message = new Message("{}".getBytes(), new MessageProperties());
    doAnswer(
            i -> {
              ((CorrelationData) i.getArgument(3))
                  .getFuture()
                  .complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(template)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    new RabbitTransport(template).send("exchange", "route", message);
    assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
  }

  @Test
  void negativeConfirmRejects() throws Exception {
    RabbitTemplate template = mock(RabbitTemplate.class);
    doAnswer(
            i -> {
              ((CorrelationData) i.getArgument(3))
                  .getFuture()
                  .complete(new CorrelationData.Confirm(false, "nack"));
              return null;
            })
        .when(template)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    assertThrows(
        IllegalStateException.class,
        () ->
            new RabbitTransport(template)
                .send("exchange", "route", new Message(new byte[0], new MessageProperties())));
  }
}
