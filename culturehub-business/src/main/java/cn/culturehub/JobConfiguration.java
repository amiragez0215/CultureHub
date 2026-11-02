package cn.culturehub;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class JobConfiguration {
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "culture.jobs.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public XxlJobSpringExecutor executor(
      @Value("${xxl.job.admin.addresses:}") String addresses,
      @Value("${xxl.job.accessToken:}") String token,
      @Value("${xxl.job.executor.address:}") String address) {
    var x = new XxlJobSpringExecutor();
    x.setAdminAddresses(addresses);
    x.setAppname("culturehub-api");
    x.setAddress(address);
    x.setPort(9999);
    x.setAccessToken(token);
    x.setLogPath("./logs/xxl-job");
    x.setLogRetentionDays(7);
    return x;
  }
}
