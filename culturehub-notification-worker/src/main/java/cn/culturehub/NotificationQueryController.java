package cn.culturehub;

import cn.culturehub.v3.ServiceBoundary;
import feign.FeignException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@Profile("v3-p1")
@RequestMapping("/api/v1/me/notifications")
class NotificationQueryController {
  private final JdbcTemplate database;
  private final CoreIdentityClient core;
  private final String credential;

  NotificationQueryController(JdbcTemplate database, CoreIdentityClient core,
      @Value("${v3.core.token}") String credential) {
    this.database = database;
    this.core = core;
    this.credential = ServiceBoundary.requireCredential(credential);
  }

  @GetMapping
  Object list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long before,
      @RequestHeader(name = ServiceBoundary.TRACE, required = false) String trace) {
    long user = activeUser(jwt, trace);
    return database.queryForList(
        "select * from notification where user_id=? and (?=0 or id<?) order by id desc limit 50",
        user, before, before);
  }

  @PatchMapping("/{id}/read")
  Object read(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
      @RequestHeader(name = ServiceBoundary.TRACE, required = false) String trace) {
    long user = activeUser(jwt, trace);
    database.update(
        "update notification set read_at=coalesce(read_at,current_timestamp) where id=? and user_id=?",
        id, user);
    return Map.of("ok", true);
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> failure(ResponseStatusException failure) {
    String code = failure.getStatusCode().value() == 401 ? "UNAUTHENTICATED" : "CORE_UNAVAILABLE";
    return ResponseEntity.status(failure.getStatusCode())
        .body(Map.of("code", code, "message", failure.getReason()));
  }

  private long activeUser(Jwt jwt, String trace) {
    long user = Long.parseLong(jwt.getSubject());
    Map<String, Boolean> result;
    try {
      result = core.active(user, "culturehub-notification", credential, ServiceBoundary.traceId(trace));
    } catch (FeignException unavailable) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity service unavailable");
    }
    if (result == null || !Boolean.TRUE.equals(result.get("active"))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account disabled");
    }
    return user;
  }
}
