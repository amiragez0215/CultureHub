package cn.culturehub;

/** Test-only process fault: actual DB commit followed by JVM death without a Rabbit ACK. */
public class LostAckWorkerProcess {
  public static void main(String[] args) throws Exception {
    var factory =
        new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(
            "127.0.0.1", Integer.parseInt(args[0]));
    factory.setUsername(System.getenv().getOrDefault("RABBIT_USER", "guest"));
    factory.setPassword(System.getenv().getOrDefault("RABBIT_PASSWORD", "guest"));
    factory.setVirtualHost(System.getenv().getOrDefault("RABBIT_VHOST", "/"));
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:13306/culturehub?connectionTimeZone=Asia/Shanghai", "root", "");
    var consumer =
        new NotificationConsumer(
            new org.springframework.jdbc.core.JdbcTemplate(source),
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    try {
      var connection = factory.createConnection();
      var channel = connection.createChannel(false);
      com.rabbitmq.client.GetResponse delivery = null;
      for (int i = 0; i < 100 && delivery == null; i++) {
        delivery = channel.basicGet(RabbitTopology.QUEUE, false);
        if (delivery == null) Thread.sleep(100);
      }
      if (delivery == null) throw new IllegalStateException("No delivery for fault worker");
      consumer.consume(new String(delivery.getBody(), java.nio.charset.StandardCharsets.UTF_8));
      System.out.println("DB committed; terminating JVM before ACK");
      System.out.flush();
      Runtime.getRuntime().halt(86);
    } finally {
      factory.destroy(); // On setup failure, exit promptly rather than leave client threads alive.
    }
  }
}
