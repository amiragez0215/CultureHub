package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** A closed Java tool catalogue. SQL remains inside the original query service. */
@Service
public class GuideTools {
  final GuideQueryService queries;

  @Value("${culture.ai.v2.candidate-limit:60}")
  int candidateLimit = 60;

  public GuideTools(GuideQueryService queries) {
    this.queries = queries;
  }

  public List<ToolCall> plan(Turn state) {
    if (state.requirements.intentType() == IntentType.VENUE_QA)
      return List.of(
          new ToolCall(
              ToolName.VENUE_INFORMATION, null, state.requirements.hardConstraints().venue()));
    return List.of(
        new ToolCall(ToolName.SEARCH_SESSIONS, null, null),
        new ToolCall(ToolName.MY_ACTIVE_BOOKINGS, null, null));
  }

  public ToolResult execute(ToolCall call, Turn state) {
    if (++state.toolCalls > 12) throw new Problem("AI_TOOL_LIMIT", "本轮业务查询预算已耗尽", 429);
    try {
      var sessions = new ArrayList<Session>();
      var venues = new ArrayList<Venue>();
      var bookings = new ArrayList<ExistingBooking>();
      switch (call.name()) {
        case SEARCH_SESSIONS ->
            queries
                .search(
                    state.requirements.hardConstraints(),
                    state.identity.ownerUserId(),
                    Math.max(20, Math.min(120, candidateLimit)))
                .forEach(row -> sessions.add(session(row)));
        case SESSION_DETAILS -> {
          Problem.require(
              call.sessionId() != null && call.sessionId() > 0, "INVALID_TOOL_INPUT", "场次编号无效");
          var value = session(queries.current(call.sessionId()));
          Problem.require(value.registrationOpen(), "SESSION_NOT_PUBLIC", "该场次未公开");
          sessions.add(value);
        }
        case VENUE_INFORMATION ->
            queries
                .venues(call.keyword())
                .forEach(
                    v ->
                        venues.add(
                            new Venue(
                                Store.id(v, "id"),
                                v.get("name").toString(),
                                Objects.toString(v.get("introduction"), ""),
                                Objects.toString(v.get("opening_hours"), ""))));
        case MY_ACTIVE_BOOKINGS ->
            queries
                .activeBookings(state.identity.ownerUserId())
                .forEach(
                    b ->
                        bookings.add(
                            new ExistingBooking(
                                Store.id(b, "id"),
                                Store.id(b, "sessionId"),
                                b.get("status").toString(),
                                GuideQueryService.time(b, "startAt"),
                                GuideQueryService.time(b, "endAt"))));
      }
      boolean empty = sessions.isEmpty() && venues.isEmpty() && bookings.isEmpty();
      return new ToolResult(
          call.name(),
          empty ? ResultStatus.EMPTY : ResultStatus.SUCCESS,
          sessions,
          bookings,
          venues,
          state.identity.currentTime(),
          null);
    } catch (Problem error) {
      throw error;
    } catch (Exception error) {
      return new ToolResult(
          call.name(),
          ResultStatus.FAILED,
          List.of(),
          List.of(),
          List.of(),
          state.identity.currentTime(),
          error.getClass().getSimpleName());
    }
  }

  public ToolCheckResult check(Turn state) {
    var missing = new ArrayList<String>();
    var required =
        state.requirements.intentType() == IntentType.VENUE_QA
            ? Set.of(ToolName.VENUE_INFORMATION)
            : Set.of(ToolName.SEARCH_SESSIONS, ToolName.MY_ACTIVE_BOOKINGS);
    for (var name : required)
      if (state.toolPlan.stream().noneMatch(c -> c.name() == name)) missing.add("plan:" + name);
    for (var call : state.toolPlan) {
      var result = state.toolResults.stream().filter(r -> r.tool() == call.name()).findFirst();
      if (result.isEmpty()) missing.add(call.name().name());
      else if (result.get().status() == ResultStatus.FAILED)
        return new ToolCheckResult(
            ResultStatus.FAILED, Failure.TOOL_EXECUTION_FAILED, List.of(call.name().name()));
    }
    if (!missing.isEmpty())
      return new ToolCheckResult(ResultStatus.PARTIAL, Failure.TOOL_PLAN_INCOMPLETE, missing);
    for (var s : state.candidateSessions)
      if (s.sessionId() <= 0
          || s.activityId() <= 0
          || s.venueId() <= 0
          || s.startAt() == null
          || s.endAt() == null
          || !s.startAt().isBefore(s.endAt()))
        return new ToolCheckResult(
            ResultStatus.PARTIAL, Failure.MISSING_BUSINESS_DATA, List.of("session fields"));
    boolean empty =
        state.requirements.intentType() != IntentType.VENUE_QA && state.candidateSessions.isEmpty();
    return new ToolCheckResult(
        empty ? ResultStatus.EMPTY : ResultStatus.SUCCESS, Failure.NONE, List.of());
  }

  public static Session session(Map<String, Object> row) {
    return new Session(
        Store.id(row, "sessionId"),
        Store.id(row, "activityId"),
        Store.id(row, "venueId"),
        Objects.toString(row.get("title"), ""),
        Objects.toString(row.get("category"), ""),
        Objects.toString(row.get("audience"), ""),
        Objects.toString(row.get("description"), ""),
        Objects.toString(row.get("venueName"), ""),
        GuideQueryService.time(row, "startAt"),
        GuideQueryService.time(row, "endAt"),
        GuideQueryService.time(row, "registrationStartAt"),
        GuideQueryService.time(row, "registrationEndAt"),
        ((Number) row.get("remaining")).intValue(),
        ((Number) row.get("waitlistPriority")).intValue() != 0,
        "PUBLISHED".equals(row.get("sessionStatus"))
            && "PUBLISHED".equals(row.get("activityStatus"))
            && "OPEN".equals(row.get("venueStatus")),
        GuideQueryService.time(row, "asOf"));
  }
}
