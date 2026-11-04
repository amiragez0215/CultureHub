package cn.culturehub;

@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class RabbitEndToEndContainerTest extends RabbitEndToEndTest {
  @org.junit.jupiter.api.Test
  void actualBrokerStopAndRestartRetainsCommittedOutbox() throws Exception {
    rabbit.getDockerClient().stopContainerCmd(rabbit.getContainerId()).withTimeout(3).exec();
    try {
      identity(1);
      booking.create(1, java.util.UUID.randomUUID().toString(), false);
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () -> new Publisher(s, transport).publish());
      org.junit.jupiter.api.Assertions.assertEquals(
          1, s.number("select count(*) from outbox where status='PENDING'"));
      org.junit.jupiter.api.Assertions.assertEquals(
          1, s.number("select occupied_count from activity_session where id=1"));
    } finally {
      rabbit.getDockerClient().startContainerCmd(rabbit.getContainerId()).exec();
    }
    boolean ready = false;
    for (int i = 0; i < 30; i++) {
      try {
        if (rabbit.execInContainer("rabbitmq-diagnostics", "-q", "ping").getExitCode() == 0) {
          ready = true;
          break;
        }
      } catch (Exception unavailable) {
      }
      Thread.sleep(1000);
    }
    org.junit.jupiter.api.Assertions.assertTrue(ready);
    s.db.update("update outbox set next_attempt_at=now()");
    new Publisher(s, transport).publish();
    awaitNotice();
  }
}
