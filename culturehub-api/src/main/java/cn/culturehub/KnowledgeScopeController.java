package cn.culturehub;

import cn.culturehub.v3.ServiceBoundary;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("v3-p1")
@RequestMapping("/api/v1/service/knowledge")
class KnowledgeScopeController {
  private final Access access;
  private final Store store;
  private final String credential;

  KnowledgeScopeController(Access access, Store store,
      @Value("${v3.internal.knowledge}") String credential) {
    this.access = access;
    this.store = store;
    this.credential = ServiceBoundary.requireCredential(credential);
  }

  @GetMapping("/scope")
  Map<String, Object> scope(
      @RequestHeader(name=ServiceBoundary.CALLER,required=false) String caller,
      @RequestHeader(name=ServiceBoundary.CREDENTIAL,required=false) String token,
      @RequestParam(defaultValue = "0") long venue,
      @RequestParam(defaultValue = "0") long activity,
      @RequestParam(defaultValue = "false") boolean write) {
    if (!"culturehub-knowledge".equals(caller)
        || !ServiceBoundary.authenticates(credential, token)) {
      throw new Problem("SERVICE_UNAUTHENTICATED", "Invalid service identity", 401);
    }
    long user = access.user();
    if (activity > 0) {
      long actual = store.number("select venue_id from activity where id=?", activity);
      Problem.require(actual == venue, "INVALID_SCOPE", "Activity belongs to another venue");
    }
    if (write) {
      if (venue == 0) access.platformOnly();
      else access.venue(venue);
    } else if (venue > 0) {
      Problem.require(store.number("select count(*) from venue where id=? and status='OPEN'", venue) == 1,
          "NOT_FOUND", "Venue unavailable");
    }
    return Map.of("userId", user, "platformAdmin", access.platform(), "venueId", venue);
  }
}
