package cn.culturehub;

import cn.culturehub.v3.ServiceBoundary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Fixed, read-only RPC catalogue. Owner is always resolved from the forwarded user JWT. */
@RestController
@Profile("v3-p1")
@RequestMapping("/api/v1/service/ai")
class AiBusinessQueryController {
  enum Tool { SEARCH_SESSIONS, SESSION_DETAILS, VENUE_INFORMATION, MY_ACTIVE_BOOKINGS }
  record Query(@NotNull Tool tool, AiGuideGraph.Preferences preferences,
               @Positive Long sessionId, @Size(max=120) String keyword, @Min(1) @Max(120) Integer limit) {}
  private final GuideQueryService queries;
  private final Access access;
  private final String credential;

  AiBusinessQueryController(GuideQueryService queries, Access access,
      @Value("${v3.internal.ai}") String credential) {
    this.queries=queries;
    this.access=access;
    this.credential=ServiceBoundary.requireCredential(credential);
  }

  @PostMapping("/query")
  Object query(@RequestHeader(name=ServiceBoundary.CALLER,required=false) String caller,
      @RequestHeader(name=ServiceBoundary.CREDENTIAL,required=false) String token, @Valid @RequestBody Query query) {
    if (!"culturehub-ai".equals(caller) || !ServiceBoundary.authenticates(credential,token)) {
      throw new Problem("SERVICE_UNAUTHENTICATED","Invalid AI service identity",401);
    }
    long owner=access.user();
    Object value=switch(query.tool()) {
      case SEARCH_SESSIONS -> {
        Problem.require(query.preferences()!=null,"INVALID_TOOL_INPUT","Search constraints required");
        try {
          yield queries.search(query.preferences(),owner,query.limit()==null?60:query.limit());
        } catch (java.time.DateTimeException invalid) {
          throw new Problem("INVALID_TOOL_INPUT","Invalid date or time constraint",400);
        }
      }
      case SESSION_DETAILS -> {
        Problem.require(query.sessionId()!=null,"INVALID_TOOL_INPUT","Session ID required");
        var card=new java.util.LinkedHashMap<>(queries.current(query.sessionId()));
        card.put("ownActiveParticipation",queries.s.number(
            "select count(*) from participation where session_id=? and user_id=? and active_guard=1",query.sessionId(),owner)>0);
        yield card;
      }
      case VENUE_INFORMATION -> queries.venues(query.keyword());
      case MY_ACTIVE_BOOKINGS -> queries.activeBookings(owner);
    };
    return Map.of("tool",query.tool(),"ownerUserId",owner,"result",value);
  }

  @GetMapping("/identity")
  Object identity(@RequestHeader(name=ServiceBoundary.CALLER,required=false) String caller,
      @RequestHeader(name=ServiceBoundary.CREDENTIAL,required=false) String token) {
    if (!"culturehub-ai".equals(caller) || !ServiceBoundary.authenticates(credential,token))
      throw new Problem("SERVICE_UNAUTHENTICATED","Invalid AI service identity",401);
    return Map.of("userId",access.user());
  }
}
