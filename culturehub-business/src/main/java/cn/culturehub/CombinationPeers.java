package cn.culturehub;

import cn.culturehub.v3.CombinationBranch;
import cn.culturehub.v3.ServiceBoundary;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.seata.core.context.RootContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile("v3-p4 & !v3-partner")
class CombinationPeers {
  private final CombinationParticipant core;
  private final Map<String, RestClient> partners;
  private final String credential;

  CombinationPeers(CombinationParticipant core, RestClient.Builder builder,
      @Value("${v3.internal.combination}") String credential,
      @Value("${V3_PARTNER_A_URL:http://partner-a:8080}") String a,
      @Value("${V3_PARTNER_B_URL:http://partner-b:8080}") String b) {
    this.core = core;
    this.credential = ServiceBoundary.requireCredential(credential);
    var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    transport.setReadTimeout(Duration.ofSeconds(4));
    partners = Map.of("PARTNER_A", builder.clone().requestFactory(transport).baseUrl(a).build(),
        "PARTNER_B", builder.clone().requestFactory(transport).baseUrl(b).build());
  }

  void prepare(String name, CombinationBranch branch) {
    if ("CORE".equals(name)) core.prepare(branch);
    else post(name, "prepare", branch, null, Void.class);
  }

  void pin(String name, CombinationBranch branch) {
    if ("CORE".equals(name)) core.pin(branch);
    else post(name, "pin", branch, null, Void.class);
  }

  CombinationBranch.Result confirm(String name, CombinationBranch branch) {
    return post(name, "confirm", branch, RootContext.getXID(), CombinationBranch.Result.class);
  }

  CombinationBranch.Proof proof(String name, CombinationBranch branch, String xid) {
    return "CORE".equals(name) ? core.proof(branch, xid)
        : post(name, "proof?xid={value}", branch, xid, CombinationBranch.Proof.class);
  }

  CombinationBranch.Settlement settle(String name, CombinationBranch branch, boolean committed) {
    return "CORE".equals(name) ? core.settle(branch, committed)
        : post(name, "settle?committed={value}", branch, committed, CombinationBranch.Settlement.class);
  }

  CombinationBranch.Single fallback(String name, CombinationBranch branch) {
    return "CORE".equals(name) ? core.fallback(branch)
        : post(name, "fallback", branch, null, CombinationBranch.Single.class);
  }

  Object catalog(String name) {
    return partners.get(name).get().uri("/internal/v1/combination/catalog")
        .header(ServiceBoundary.CALLER, "culturehub-core")
        .header(ServiceBoundary.CREDENTIAL, credential).retrieve().body(List.class);
  }

  private <T> T post(String name, String path, CombinationBranch branch, Object value, Class<T> type) {
    String endpoint = "/internal/v1/combination/" + path;
    var request = (path.contains("{value}") ? partners.get(name).post().uri(endpoint, value)
        : partners.get(name).post().uri(endpoint))
        .header(ServiceBoundary.CALLER, "culturehub-core")
        .header(ServiceBoundary.CREDENTIAL, credential)
        .header(ServiceBoundary.TRACE, ServiceBoundary.traceId(branch.trace()));
    if ("confirm".equals(path)) request.header(RootContext.KEY_XID, (String) value);
    return request.body(branch).retrieve().body(type);
  }
}
