package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bounded deterministic same-venue interval scheduling, independent of model arithmetic. */
@Component
public class GuidePlanner {
  public List<Combination> optimize(Turn s) {
    var candidates =
        s.candidateSessions.stream()
            .filter(c -> eligible(c, s))
            .sorted(
                Comparator.comparingDouble((Session c) -> score(c, s))
                    .reversed()
                    .thenComparing(Session::startAt)
                    .thenComparingLong(Session::sessionId))
            .limit(60)
            .toList();
    s.rankedCandidates = candidates;
    var combinations = new ArrayList<Combination>();
    int count = s.requirements.requestedActivityCount();
    enumerate(s, candidates, 0, count, new ArrayList<>(), combinations);
    if (combinations.isEmpty() && s.requirements.countPreferred())
      for (int i = count - 1; i >= 1 && combinations.isEmpty(); i--)
        enumerate(s, candidates, 0, i, new ArrayList<>(), combinations);
    return combinations.stream()
        .filter(c -> revisionMatches(s, c))
        .sorted(
            Comparator.comparingDouble(Combination::score)
                .reversed()
                .thenComparing(c -> c.sessions().get(0).startAt()))
        .limit(5)
        .toList();
  }

  boolean revisionMatches(Turn s, Combination combination) {
    if (s.requirements.revisionIndex() == null || s.previousPlan == null) return true;
    int changed = s.requirements.revisionIndex() - 1;
    var old = s.previousPlan.scheduleItems();
    var next = combination.sessions();
    if (changed < 0 || changed >= old.size() || next.size() != old.size()) return false;
    for (int i = 0; i < old.size(); i++) {
      if (i != changed && old.get(i).sessionId() != next.get(i).sessionId()) return false;
      if (i == changed) {
        if (old.get(i).sessionId() == next.get(i).sessionId()) return false;
        if (s.identity.latestUserMessage().contains("早")
            && !next.get(i).startAt().isBefore(old.get(i).startAt())) return false;
        if (s.identity.latestUserMessage().contains("晚")
            && !next.get(i).startAt().isAfter(old.get(i).startAt())) return false;
      }
    }
    return true;
  }

  public boolean eligible(Session c, Turn s) {
    var r = s.requirements;
    var p = r.hardConstraints();
    var start = c.startAt();
    var end = c.endAt();
    if (start == null
        || end == null
        || !start.isBefore(end)
        || !c.registrationOpen()
        || !s.identity.currentTime().isBefore(start)
        || s.identity.currentTime().isBefore(c.registrationStartAt())
        || !s.identity.currentTime().isBefore(c.registrationEndAt())) return false;
    // Search may still display waitlist choices; a timed plan never presents them as available
    // seats.
    boolean search =
        r.requestedActivityCount() == 1
            && (r.intentType() == IntentType.ACTIVITY_SEARCH
                || r.intentType() == IntentType.BOOKING_REQUEST);
    if (p.minimumRemaining() != null && c.remaining() < p.minimumRemaining()) return false;
    if (!search && (c.remaining() < 1 || c.waitlistPriority())) return false;
    if (p.fromDate() != null && start.toLocalDate().isBefore(LocalDate.parse(p.fromDate())))
      return false;
    if (p.toDate() != null && end.toLocalDate().isAfter(LocalDate.parse(p.toDate()))) return false;
    if (p.fromTime() != null && start.toLocalTime().isBefore(LocalTime.parse(p.fromTime())))
      return false;
    if (p.toTime() != null
        && (end.toLocalTime().isAfter(LocalTime.parse(p.toTime()))
            || !start.toLocalDate().equals(end.toLocalDate()))) return false;
    if (p.category() != null && !p.category().equals(c.category())) return false;
    if (p.audience() != null && !p.audience().equals(c.audience()) && !"ALL".equals(c.audience()))
      return false;
    if (p.venue() != null && !c.venueName().contains(p.venue())) return false;
    if (p.keyword() != null && !(c.title() + c.description()).contains(p.keyword())) return false;
    return s.existingBookings.stream().noneMatch(b -> overlaps(start, end, b.startAt(), b.endAt()));
  }

  public static boolean overlaps(
      LocalDateTime start, LocalDateTime end, LocalDateTime otherStart, LocalDateTime otherEnd) {
    return start.isBefore(otherEnd) && otherStart.isBefore(end);
  }

  double score(Session c, Turn s) {
    double score = 0;
    for (String preference : s.requirements.softPreferences())
      if ((c.title() + c.description() + c.category() + c.venueName()).contains(preference))
        score += 10;
    return score;
  }

  void enumerate(
      Turn s,
      List<Session> candidates,
      int offset,
      int count,
      List<Session> chosen,
      List<Combination> output) {
    if (chosen.size() == count) {
      var ordered = chosen.stream().sorted(Comparator.comparing(Session::startAt)).toList();
      long venue = ordered.get(0).venueId();
      if (ordered.stream().map(Session::activityId).distinct().count() != ordered.size()) return;
      if (ordered.stream().anyMatch(c -> c.venueId() != venue)) return;
      for (int i = 1; i < ordered.size(); i++)
        if (ordered
            .get(i - 1)
            .endAt()
            .plusMinutes(s.requirements.minimumGapMinutes())
            .isAfter(ordered.get(i).startAt())) return;
      long minutes =
          Duration.between(ordered.get(0).startAt(), ordered.get(ordered.size() - 1).endAt())
              .toMinutes();
      if (minutes > s.requirements.planningDurationMinutes()) return;
      output.add(
          new Combination(
              ordered,
              ordered.stream().mapToDouble(c -> score(c, s)).sum() - minutes / 1440.0,
              List.of("同一场馆", "时间不冲突", "满足已核验的业务条件")));
      return;
    }
    for (int i = offset; i < candidates.size(); i++) {
      Session next = candidates.get(i);
      if (!chosen.isEmpty() && chosen.get(0).venueId() != next.venueId()) continue;
      chosen.add(next);
      enumerate(s, candidates, i + 1, count, chosen, output);
      chosen.remove(chosen.size() - 1);
    }
  }

  public Verification verify(Turn s, Proposal p) {
    var errors = new ArrayList<Violation>();
    if (p == null
        || p.title() == null
        || p.orderedSessionIds() == null
        || p.scheduleItems() == null
        || p.evidenceReferences() == null
        || p.recommendationReasons() == null
        || p.scheduleItems().stream().anyMatch(Objects::isNull)
        || p.orderedSessionIds().stream().anyMatch(Objects::isNull))
      return new Verification(
          false,
          List.of(
              new Violation(
                  Failure.OUTPUT_FORMAT_INVALID,
                  "proposal",
                  List.of(),
                  "方案结构不完整",
                  "ProposalGenerate",
                  true)));
    var byId = new HashMap<Long, Session>();
    s.candidateSessions.forEach(c -> byId.put(c.sessionId(), c));
    if (!Objects.equals(s.identity.turnId(), p.planId())
        || s.currentPlanVersion != p.planVersion()
        || p.generatedAt() == null)
      errors.add(
          new Violation(
              Failure.OUTPUT_FORMAT_INVALID,
              "planVersion",
              List.of(),
              "方案身份或版本不正确",
              "ProposalGenerate",
              true));
    if (s.requirements.intentType() != IntentType.VENUE_QA) {
      boolean knownCombination =
          s.activityCombinations.stream()
              .anyMatch(
                  c ->
                      c.sessions().stream()
                          .map(Session::sessionId)
                          .toList()
                          .equals(p.orderedSessionIds()));
      if (!knownCombination)
        errors.add(
            new Violation(
                Failure.SCHEDULE_CONFLICT,
                "orderedSessionIds",
                p.orderedSessionIds(),
                "组合不在已核验的可行集合中",
                "ProposalGenerate",
                true));
      if (p.scheduleItems().size() != p.orderedSessionIds().size())
        errors.add(
            new Violation(
                Failure.OUTPUT_FORMAT_INVALID,
                "scheduleItems",
                List.of(),
                "时间项数量不一致",
                "ProposalGenerate",
                true));
      if (!p.scheduleItems().stream()
          .map(ScheduleItem::sessionId)
          .toList()
          .equals(p.orderedSessionIds()))
        errors.add(
            new Violation(
                Failure.FACT_MISMATCH,
                "scheduleItems",
                p.orderedSessionIds(),
                "时间表顺序或场次与选择不一致",
                "ProposalGenerate",
                true));
      var selected =
          p.orderedSessionIds().stream().map(byId::get).filter(Objects::nonNull).toList();
      if (!selected.isEmpty() && selected.size() == p.orderedSessionIds().size()) {
        boolean bad =
            selected.stream().map(Session::venueId).distinct().count() != 1
                || selected.stream().map(Session::activityId).distinct().count() != selected.size()
                || Duration.between(
                            selected.get(0).startAt(), selected.get(selected.size() - 1).endAt())
                        .toMinutes()
                    > s.requirements.planningDurationMinutes()
                || selected.size() > s.requirements.requestedActivityCount()
                || !s.requirements.countPreferred()
                    && selected.size() != s.requirements.requestedActivityCount();
        for (int i = 1; i < selected.size(); i++)
          if (selected
              .get(i - 1)
              .endAt()
              .plusMinutes(s.requirements.minimumGapMinutes())
              .isAfter(selected.get(i).startAt())) bad = true;
        if (bad)
          errors.add(
              new Violation(
                  Failure.SCHEDULE_CONFLICT,
                  "activityCombinations",
                  p.orderedSessionIds(),
                  "独立检查发现时间、场馆、数量或间隔冲突",
                  knownCombination ? "CandidateOptimize" : "ProposalGenerate",
                  true));
      }
      for (var item : p.scheduleItems()) {
        Session c = byId.get(item.sessionId());
        if (c == null
            || !Objects.equals(c.startAt(), item.startAt())
            || !Objects.equals(c.endAt(), item.endAt()))
          errors.add(
              new Violation(
                  Failure.FACT_MISMATCH,
                  "scheduleItems",
                  List.of(item.sessionId()),
                  "场次或时间与数据库快照不一致",
                  "ProposalGenerate",
                  true));
        else if (!eligible(c, s))
          errors.add(
              new Violation(
                  Failure.SCHEDULE_CONFLICT,
                  "candidateSessions",
                  List.of(item.sessionId()),
                  "候选本身不满足条件",
                  "CandidateOptimize",
                  true));
      }
    }
    Set<String> allowed = new HashSet<>();
    s.evidenceGrade.stream()
        .filter(g -> g.status() == GradeStatus.SUPPORTED)
        .forEach(g -> allowed.addAll(g.evidenceIds()));
    if (!allowed.containsAll(p.evidenceReferences()))
      errors.add(
          new Violation(
              Failure.FACT_MISMATCH,
              "evidenceReferences",
              List.of(),
              "引用不存在的证据",
              "ProposalGenerate",
              true));
    Set<String> reasons = Set.of("同一场馆", "时间不冲突", "满足已核验的业务条件", "公开知识咨询", "偏好匹配");
    if (!reasons.containsAll(p.recommendationReasons()) || !"文化活动计划".equals(p.title()))
      errors.add(
          new Violation(
              Failure.FACT_MISMATCH,
              "recommendationReasons",
              List.of(),
              "包含未经验证的事实性承诺",
              "ProposalGenerate",
              true));
    return new Verification(errors.isEmpty(), errors);
  }

  public String repair(Turn s) {
    String target = s.repairTarget;
    int limit =
        switch (target == null ? "" : target) {
          case "ToolPlan", "ToolExecute" -> 2;
          case "RetrievalPlan", "CandidateOptimize", "ProposalGenerate" -> 1;
          default -> 0;
        };
    String budgetKey = target != null && target.startsWith("Tool") ? "business" : target;
    int used = s.repairCounters.getOrDefault(budgetKey, 0);
    if (limit == 0 || used >= limit) {
      s.phase = "FAILED";
      return "stop";
    }
    s.repairCounters.put(budgetKey, used + 1);
    return target;
  }
}
