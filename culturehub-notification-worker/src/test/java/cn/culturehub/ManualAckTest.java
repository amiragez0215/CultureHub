package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;

class ManualAckTest {
  final NotificationConsumer consumer = mock(NotificationConsumer.class);
  final RabbitTransport transport = mock(RabbitTransport.class);
  final Channel channel = mock(Channel.class);

  Message message(int attempt) {
    var props = new MessageProperties();
    props.setDeliveryTag(7);
    props.setMessageId("stable-id");
    props.setHeader("x-retry-attempt", attempt);
    return new Message("{}".getBytes(), props);
  }

  @Test
  void ackOnlyAfterConsumerReturns() throws Exception {
    new RabbitNotificationListener(consumer, transport).receive(message(0), channel);
    var order = inOrder(consumer, channel);
    order.verify(consumer).consume("{}");
    order.verify(channel).basicAck(7, false);
  }

  @Test
  void failedTransactionRequiresConfirmedRetryBeforeAck() throws Exception {
    doThrow(new org.springframework.dao.TransientDataAccessResourceException("offline"))
        .when(consumer)
        .consume(anyString());
    new RabbitNotificationListener(consumer, transport).receive(message(0), channel);
    var order = inOrder(transport, channel);
    order
        .verify(transport)
        .send(
            eq(RabbitTopology.RECOVERY),
            eq("retry"),
            argThat(
                m ->
                    Integer.valueOf(1).equals(m.getMessageProperties().getHeader("x-retry-attempt"))
                        && "stable-id".equals(m.getMessageProperties().getMessageId())));
    order.verify(channel).basicAck(7, false);
  }

  @Test
  void failedRecoverySendNeverAcknowledgesOriginal() throws Exception {
    doThrow(new org.springframework.dao.TransientDataAccessResourceException("offline"))
        .when(consumer)
        .consume(anyString());
    doThrow(new IllegalStateException("unconfirmed"))
        .when(transport)
        .send(anyString(), anyString(), any());
    assertThrows(
        Exception.class,
        () -> new RabbitNotificationListener(consumer, transport).receive(message(0), channel));
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(channel).abort();
  }

  @Test
  void poisonIsIsolatedWithoutRetry() throws Exception {
    doThrow(new IllegalArgumentException("invalid")).when(consumer).consume(anyString());
    new RabbitNotificationListener(consumer, transport).receive(message(0), channel);
    verify(transport).send(eq(RabbitTopology.RECOVERY), eq("dead"), any());
    verify(channel).basicAck(7, false);
  }

  @Test
  void boundedRetriesReachDeadQueue() throws Exception {
    doThrow(new org.springframework.dao.TransientDataAccessResourceException("offline"))
        .when(consumer)
        .consume(anyString());
    new RabbitNotificationListener(consumer, transport).receive(message(3), channel);
    verify(transport).send(eq(RabbitTopology.RECOVERY), eq("dead"), any());
    verify(channel).basicAck(7, false);
  }
}
