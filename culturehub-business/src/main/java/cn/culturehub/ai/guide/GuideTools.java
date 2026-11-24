package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** A closed Java tool catalogue. SQL remains inside the original query service. */
@org.springframework.context.annotation.Profile("!v3-p5 & !v3-partner")
@Service
public class GuideTools {
  final GuideQueryService queries;
  final org.springframework.beans.factory.ObjectProvider<GuideLanguageModel> model;

  @Value("${culture.ai.v2.candidate-limit:60}")
  int candidateLimit = 60;

  public GuideTools(GuideQueryService queries) {
    this(queries, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public GuideTools(
      GuideQueryService queries,
      org.springframework.beans.factory.ObjectProvider<GuideLanguageModel> model) {
    this.queries = queries;
    this.model = model;
  }

  static List<Need> needs(Turn state) {
    var requirements = state.requirements;
    var filters = requirements.hardConstraints();
    if (requirements.intentType() == IntentType.VENUE_QA && !state.detailOnly) {
      if (filters.venue() == null) return List.of();
      return List.of(
          new Need(
              "venue",
              "读取指定场馆",
              null,
              true,
              ToolName.VENUE_INFORMATION,
              new ToolArguments(null, filters.venue(), null)));
    }
    Long session = requestedSession(state.identity.latestUserMessage());
    var needs = new ArrayList<Need>();
    needs.add(
        session == null
            ? new Need(
                "sessions",
                "查询满足条件的场次",
                null,
                true,
                ToolName.SEARCH_SESSIONS,
                new ToolArguments(null, null, filters))
            : new Need(
                "session:" + session,
                "核对指定场次",
                null,
                true,
                ToolName.SESSION_DETAILS,
                new ToolArguments(session, null, null)));
    if (!state.detailOnly
        && (requirements.intentType() != IntentType.ACTIVITY_SEARCH
            || requirements.requestedActivityCount() > 1
            || state.identity.latestUserMessage().matches("(?s).*(避开|冲突|已有预约|已约|我约过).*")))
      needs.add(
          new Need(
              "bookings",
              "读取本人的有效参与时间",
              null,
              true,
              ToolName.MY_ACTIVE_BOOKINGS,
              new ToolArguments(null, null, null)));
    return List.copyOf(needs);
  }

  static Long requestedSession(String message) {
    var id =
        java.util.regex.Pattern.compile(
                "(?:场次(?:编号|号|ID)?\\s*[:：#]?\\s*(\\d{1,18})|(\\d{1,18})\\s*号场次)",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(message);
    if (!id.find()) return null;
    return Long.valueOf(id.group(1) == null ? id.group(2) : id.group(1));
  }

  List<Need> businessNeeds(Turn state) {
    var planned = state.informationNeeds.stream().filter(need -> need.tool() != null).toList();
    return planned.isEmpty() ? needs(state) : planned;
  }

  public List<ToolCall> plan(Turn state) {
    return businessNeeds(state).stream()
        .map(n -> new ToolCall(n.tool(), n.arguments(), n.id(), n.required(), n.dependency()))
        .filter(call -> state.toolResults.stream().noneMatch(r -> reusable(call, r, state)))
        .toList();
  }

  boolean reusable(ToolCall call, ToolResult result, Turn state) {
    if (!matches(call, result)
        || result.arguments() == null
        || result.status() != ResultStatus.SUCCESS && result.status() != ResultStatus.EMPTY
        || result.status() == ResultStatus.SUCCESS && !complete(call, result)
        || result.queriedAt() == null) return false;
    long age = Duration.between(result.queriedAt(), state.identity.currentTime()).toSeconds();
    return age >= 0 && age < (call.name() == ToolName.MY_ACTIVE_BOOKINGS ? 10 : 30);
  }

  boolean matches(ToolCall call, ToolResult result) {
    // Older checkpoint results have no arguments. They can be checked but never reused.
    return call.name() == result.tool()
        && (result.arguments() == null
            || call.informationNeedId().equals(result.informationNeedId())
                && call.arguments().equals(result.arguments()));
  }

  public ToolResult execute(ToolCall call, Turn state) {
    validate(call);
    if (++state.toolCalls > 12) throw new Problem("AI_TOOL_LIMIT", "只读业务查询预算已耗尽", 429);
    try {
      var nativeModel =
          call.name() == ToolName.SESSION_DETAILS && model != null && state.llmCalls < 3
              ? model.getIfAvailable()
              : null;
      var result =
          nativeModel == null
              ? dispatch(call, state)
              : nativeModel.sessionDetailsTool(
                  state, call.sessionId(), () -> dispatch(call, state));
      return result.forCall(call);
    } catch (Problem error) {
      if (Set.of("NOT_FOUND", "SESSION_NOT_PUBLIC").contains(error.code))
        return empty(call, state, error.code);
      throw error;
    } catch (Exception error) {
      String kind =
          error instanceof org.springframework.dao.QueryTimeoutException
              ? "MYSQL_TIMEOUT"
              : error.getClass().getSimpleName();
      return new ToolResult(
              call.name(),
              ResultStatus.FAILED,
              List.of(),
              List.of(),
              List.of(),
              state.identity.currentTime(),
              kind)
          .forCall(call);
    }
  }

  void validate(ToolCall call) {
    Problem.require(
        call != null
            && call.name() != null
            && call.arguments() != null
            && call.informationNeedId() != null,
        "INVALID_TOOL_INPUT",
        "工具调用格式不正确");
    var a = call.arguments();
    boolean valid =
        switch (call.name()) {
          case SEARCH_SESSIONS -> a.sessionId() == null && a.keyword() == null;
          case MY_ACTIVE_BOOKINGS ->
              a.sessionId() == null && a.keyword() == null && a.filters() == null;
          case SESSION_DETAILS ->
              a.sessionId() != null
                  && a.sessionId() > 0
                  && a.keyword() == null
                  && a.filters() == null;
          case VENUE_INFORMATION ->
              a.sessionId() == null
                  && a.keyword() != null
                  && !a.keyword().isBlank()
                  && a.keyword().length() <= 120
                  && a.filters() == null;
        };
    Problem.require(valid, "INVALID_TOOL_INPUT", "工具参数与查询用途不匹配");
  }

  private ToolResult dispatch(ToolCall call, Turn state) {
    var sessions = new ArrayList<Session>();
    var venues = new ArrayList<Venue>();
    var bookings = new ArrayList<ExistingBooking>();
    switch (call.name()) {
      case SEARCH_SESSIONS ->
          queries
              .search(
                  call.arguments().filters() == null
                      ? state.requirements.hardConstraints()
                      : call.arguments().filters(),
                  state.identity.ownerUserId(),
                  Math.max(21, Math.min(120, candidateLimit)))
              .forEach(row -> sessions.add(session(row)));
      case SESSION_DETAILS -> {
        var value = session(queries.current(call.sessionId()));
        if (!value.registrationOpen()) return empty(call, state, "SESSION_NOT_PUBLIC");
        sessions.add(value);
      }
      case VENUE_INFORMATION -> {
        var rows = queries.venues(call.keyword());
        var exact = rows.stream().filter(v -> call.keyword().equals(v.get("name"))).toList();
        if (!exact.isEmpty()) rows = exact;
        if (rows.size() > 1)
          return new ToolResult(
              call.name(),
              ResultStatus.PARTIAL,
              List.of(),
              List.of(),
              List.of(),
              state.identity.currentTime(),
              "VENUE_AMBIGUOUS");
        rows.forEach(
            v ->
                venues.add(
                    new Venue(
                        Store.id(v, "id"),
                        Objects.toString(v.get("name"), ""),
                        Objects.toString(v.get("introduction"), ""),
                        Objects.toString(v.get("opening_hours"), ""))));
      }
      case MY_ACTIVE_BOOKINGS ->
          queries
              .activeBookings(state.identity.ownerUserId())
              .forEach(
                  b ->
                      bookings.add(
                          new ExistingBooking(
                              Store.id(b, "id"),
                              Store.id(b, "sessionId"),
                              Objects.toString(b.get("status"), ""),
                              GuideQueryService.time(b, "startAt"),
                              GuideQueryService.time(b, "endAt"))));
    }
    if (sessions.isEmpty() && venues.isEmpty() && bookings.isEmpty())
      return empty(
          call, state, call.name() == ToolName.VENUE_INFORMATION ? "VENUE_NOT_FOUND" : null);
    return new ToolResult(
        call.name(),
        ResultStatus.SUCCESS,
        sessions,
        bookings,
        venues,
        state.identity.currentTime(),
        null);
  }

  private ToolResult empty(ToolCall call, Turn state, String code) {
    return new ToolResult(
            call.name(),
            ResultStatus.EMPTY,
            List.of(),
            List.of(),
            List.of(),
            state.identity.currentTime(),
            code)
        .forCall(call);
  }

  public ToolCheckResult check(Turn state) {
    var required = businessNeeds(state).stream().filter(Need::required).toList();
    var missing = new ArrayList<String>();
    var failed = new ArrayList<String>();
    var incomplete = new ArrayList<String>();
    for (var need : required) {
      var call =
          new ToolCall(
              need.tool(), need.arguments(), need.id(), need.required(), need.dependency());
      var result =
          state.toolResults.stream().filter(r -> matches(call, r)).findFirst().orElse(null);
      if (result == null) missing.add(need.id());
      else if (result.status() == ResultStatus.FAILED) failed.add(need.id());
      else if (result.status() == ResultStatus.PARTIAL
          || result.status() == ResultStatus.SKIPPED
          || result.status() == ResultStatus.SUCCESS && !complete(call, result)) {
        incomplete.add(need.id());
      }
    }
    if (!missing.isEmpty())
      return new ToolCheckResult(ResultStatus.PARTIAL, Failure.TOOL_PLAN_INCOMPLETE, missing);
    if (!failed.isEmpty())
      return new ToolCheckResult(ResultStatus.FAILED, Failure.TOOL_EXECUTION_FAILED, failed);
    if (!incomplete.isEmpty())
      return new ToolCheckResult(ResultStatus.PARTIAL, Failure.MISSING_BUSINESS_DATA, incomplete);
    boolean empty =
        required.stream()
            .filter(n -> n.tool() != ToolName.MY_ACTIVE_BOOKINGS)
            .anyMatch(
                n ->
                    state.toolResults.stream()
                        .anyMatch(r -> r.tool() == n.tool() && r.status() == ResultStatus.EMPTY));
    return new ToolCheckResult(
        empty ? ResultStatus.EMPTY : ResultStatus.SUCCESS, Failure.NONE, List.of());
  }

  private boolean complete(ToolCall call, ToolResult result) {
    return switch (call.name()) {
      case SEARCH_SESSIONS, SESSION_DETAILS ->
          !result.sessions().isEmpty()
              && result.sessions().stream()
                  .allMatch(
                      s ->
                          s.sessionId() > 0
                              && s.activityId() > 0
                              && s.venueId() > 0
                              && s.title() != null
                              && !s.title().isBlank()
                              && s.startAt() != null
                              && s.endAt() != null
                              && s.registrationStartAt() != null
                              && s.registrationEndAt() != null
                              && s.startAt().isBefore(s.endAt())
                              && (call.sessionId() == null || call.sessionId() == s.sessionId()));
      case VENUE_INFORMATION ->
          !result.venues().isEmpty()
              && result.venues().stream()
                  .allMatch(v -> v.venueId() > 0 && v.name() != null && !v.name().isBlank());
      case MY_ACTIVE_BOOKINGS ->
          !result.bookings().isEmpty()
              && result.bookings().stream()
                  .allMatch(
                      b ->
                          b.participationId() > 0
                              && b.sessionId() > 0
                              && b.startAt() != null
                              && b.endAt() != null
                              && b.startAt().isBefore(b.endAt()));
    };
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
