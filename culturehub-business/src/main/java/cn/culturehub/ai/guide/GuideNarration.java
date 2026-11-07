package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Model orders and styles references. The visible factual clauses come from verified Java data. */
final class GuideNarration {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final Map<String, String> CATEGORY =
      Map.of(
          "HERITAGE",
          "非遗体验",
          "LECTURE",
          "文化讲座",
          "ART",
          "艺术活动",
          "FAMILY",
          "亲子活动",
          "TRADITION",
          "传统文化",
          "CULTURE",
          "文化活动");
  private static final Set<String> LEGACY_REASONS =
      Set.of("同一场馆", "时间不冲突", "硬约束已按业务数据核验", "场馆知识查询", "偏好匹配");

  private GuideNarration() {}

  static List<RecommendationFact> facts(Turn s) {
    var selected = GuideEvidenceScope.selected(s);
    var facts = new ArrayList<RecommendationFact>();
    for (var session : selected) {
      String name = "《" + session.title() + "》";
      String category = CATEGORY.get(session.category());
      if (category != null)
        facts.add(
            new RecommendationFact(
                "category:" + session.sessionId(), session.sessionId(), name + "属于" + category));
      var filters = s.requirements.hardConstraints();
      if (filters.fromTime() != null
          || filters.toTime() != null
          || filters.fromDate() != null
          || filters.toDate() != null)
        facts.add(
            new RecommendationFact(
                "window:" + session.sessionId(),
                session.sessionId(),
                name
                    + "安排在"
                    + session.startAt().toLocalDate()
                    + " "
                    + session.startAt().format(TIME)
                    + "–"
                    + session.endAt().format(TIME)
                    + "，符合你的时间范围"));
      var matched =
          s.requirements.softPreferences().stream()
              .filter(p -> !p.isBlank() && p.length() <= 24)
              .filter(
                  p ->
                      (session.title()
                              + " "
                              + session.description()
                              + " "
                              + session.venueName()
                              + " "
                              + Objects.toString(category, ""))
                          .contains(p))
              .distinct()
              .limit(2)
              .toList();
      if (!matched.isEmpty())
        facts.add(
            new RecommendationFact(
                "interest:" + session.sessionId(),
                session.sessionId(),
                name + "的活动资料匹配你偏好的「" + String.join("、", matched) + "」"));
    }
    for (int i = 1; i < selected.size(); i++) {
      var previous = selected.get(i - 1);
      var next = selected.get(i);
      long gap = Duration.between(previous.endAt(), next.startAt()).toMinutes();
      facts.add(
          new RecommendationFact(
              "gap:" + previous.sessionId() + ":" + next.sessionId(),
              next.sessionId(),
              "《"
                  + previous.title()
                  + "》与《"
                  + next.title()
                  + "》同在"
                  + next.venueName()
                  + "，中间留有"
                  + gap
                  + "分钟衔接时间"));
    }
    return List.copyOf(facts);
  }

  static Optional<String> statement(
      Turn s, RecommendationRef reference, List<EvidenceBinding> bindings) {
    if (reference == null) return Optional.empty();
    if (reference.factId() != null) {
      if (reference.evidenceId() != null || reference.questionId() != null) return Optional.empty();
      return facts(s).stream()
          .filter(
              f ->
                  f.factId().equals(reference.factId())
                      && Objects.equals(f.sessionId(), reference.sessionId()))
          .map(RecommendationFact::statement)
          .findFirst();
    }
    if (reference.evidenceId() == null || reference.questionId() == null) return Optional.empty();
    boolean bound =
        bindings.stream()
            .anyMatch(
                b ->
                    b.status() == GradeStatus.SUPPORTED
                        && Objects.equals(reference.sessionId(), b.sessionId())
                        && reference.questionId().equals(b.questionId())
                        && b.evidenceIds().contains(reference.evidenceId()));
    if (!bound) return Optional.empty();
    var claims =
        s.evidenceGrade.stream()
            .filter(g -> reference.questionId().equals(g.questionId()))
            .flatMap(g -> g.claims().stream())
            .filter(c -> reference.evidenceId().equals(c.evidenceId()))
            .map(Claim::statement)
            .distinct()
            .toList();
    return claims.isEmpty() ? Optional.empty() : Optional.of(String.join("。", claims));
  }

  static boolean valid(Turn s, Proposal p, List<EvidenceBinding> bindings) {
    if (p.recommendationRefs().size() > 8
        || p.recommendationRefs().stream().anyMatch(r -> statement(s, r, bindings).isEmpty()))
      return false;
    var reasons = reasons(s, p, bindings);
    return p.recommendationReasons().stream().allMatch(r -> r != null && LEGACY_REASONS.contains(r))
        || reasons.equals(p.recommendationReasons());
  }

  private static List<String> reasons(Turn s, Proposal p, List<EvidenceBinding> bindings) {
    if (p.recommendationRefs().isEmpty()) {
      var facts = facts(s).stream().map(RecommendationFact::statement).limit(6).toList();
      return facts.isEmpty()
          ? p.recommendationReasons().stream().filter(LEGACY_REASONS::contains).toList()
          : facts;
    }
    return p.recommendationRefs().stream()
        .filter(Objects::nonNull)
        .flatMap(
            ref ->
                statement(s, ref, bindings).stream()
                    .map(
                        statement ->
                            ref.style() == NarrationStyle.EXPLAIN
                                ? "推荐依据：" + statement
                                : statement))
        .distinct()
        .toList();
  }

  static Proposal publish(Turn s) {
    var p = s.proposal;
    var refs = p.recommendationRefs();
    if (refs.isEmpty())
      refs =
          facts(s).stream()
              .limit(6)
              .map(
                  f ->
                      new RecommendationRef(
                          f.factId(), f.sessionId(), null, null, NarrationStyle.DIRECT))
              .toList();
    var evidence = new LinkedHashSet<>(p.evidenceReferences());
    refs.stream()
        .map(RecommendationRef::evidenceId)
        .filter(Objects::nonNull)
        .forEach(evidence::add);
    return new Proposal(
        p.planId(),
        p.planVersion(),
        p.title(),
        p.orderedSessionIds(),
        p.scheduleItems(),
        reasons(s, p, s.evidenceBindings),
        List.copyOf(evidence),
        s.unresolvedKnowledge,
        List.of("尚未预约"),
        p.generatedAt(),
        refs);
  }
}
