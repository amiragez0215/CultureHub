package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import java.util.*;

/** Knowledge scope is determined by the combination currently under consideration. */
final class GuideEvidenceScope {
  private GuideEvidenceScope() {}

  static List<Session> selected(Turn turn) {
    return turn.activityCombinations.isEmpty()
        ? List.of()
        : turn.activityCombinations.get(turn.evidenceCandidateIndex).sessions();
  }

  static List<RetrievalNeed> needs(Turn turn, List<Session> selected) {
    String message = turn.identity.latestUserMessage();
    if (turn.requirements.intentType() == IntentType.VENUE_QA) {
      if (turn.requirements.hardConstraints().venue() == null)
        return List.of(new RetrievalNeed("global", message, null, null, "BACKGROUND", true));
      return turn.toolResults.stream()
          .flatMap(r -> r.venues().stream())
          .limit(1)
          .map(
              v ->
                  new RetrievalNeed(
                      "rules:" + v.venueId(), message, v.venueId(), null, "VENUE_RULE", true))
          .toList();
    }
    if (selected.isEmpty()) return List.of();
    var result = new ArrayList<RetrievalNeed>();
    long venue = selected.get(0).venueId();
    boolean age = turn.requirements.hardConstraints().childAge() != null;
    if (age)
      result.add(
          new RetrievalNeed(
              "rules:" + venue,
              message,
              venue,
              null,
              "AGE_RULE",
              message.matches("(?s).*(必须|确认|能否|可否|能不能|可以参加吗).*")));
    if (message.matches("(?s).*(背景|历史|来源).*"))
      for (var session : selected)
        result.add(
            new RetrievalNeed(
                "activity:" + session.activityId(),
                "活动背景 " + session.title(),
                venue,
                session.activityId(),
                "ACTIVITY_BACKGROUND",
                false));
    return List.copyOf(result);
  }

  static boolean scoped(Evidence evidence, RetrievalNeed need) {
    String scope =
        need.activityId() != null ? "ACTIVITY" : need.venueId() == null ? "GLOBAL" : "VENUE";
    return scope.equals(evidence.scope())
        && Objects.equals(evidence.venueId(), need.venueId())
        && Objects.equals(evidence.activityId(), need.activityId())
        && evidence.knowledgeType().equals(need.knowledgeType());
  }

  static List<EvidenceBinding> bind(Turn turn) {
    var selected = selected(turn);
    var bindings = new ArrayList<EvidenceBinding>();
    for (var need : turn.retrievalNeeds) {
      var grade =
          turn.evidenceGrade.stream()
              .filter(g -> need.questionId().equals(g.questionId()))
              .findFirst()
              .orElse(null);
      List<String> ids = grade == null ? List.of() : grade.evidenceIds();
      var scopedIds =
          ids.stream()
              .filter(
                  id ->
                      turn.rerankedDocuments.stream()
                          .anyMatch(e -> id.equals(e.chunkId()) && scoped(e, need)))
              .distinct()
              .toList();
      var status = grade == null ? GradeStatus.INSUFFICIENT : grade.status();
      if (status == GradeStatus.SUPPORTED && (ids.isEmpty() || !scopedIds.containsAll(ids)))
        status = GradeStatus.INSUFFICIENT;
      if (selected.isEmpty()) {
        bindings.add(
            new EvidenceBinding(
                null,
                need.venueId(),
                need.activityId(),
                need.questionId(),
                need.required(),
                status,
                scopedIds));
      } else {
        for (var session : selected) {
          if (!Objects.equals(need.venueId(), session.venueId())
              || need.activityId() != null && need.activityId() != session.activityId()) continue;
          bindings.add(
              new EvidenceBinding(
                  session.sessionId(),
                  session.venueId(),
                  session.activityId(),
                  need.questionId(),
                  need.required(),
                  status,
                  scopedIds));
        }
      }
    }
    return List.copyOf(bindings);
  }

  static void reset(Turn turn) {
    turn.retrievalNeeds = needs(turn, selected(turn));
    turn.retrievalPlan = List.of();
    turn.retrievedDocuments = List.of();
    turn.rerankedDocuments = List.of();
    turn.evidenceGrade = List.of();
    turn.evidenceBindings = List.of();
    turn.supportedClaims = List.of();
    turn.unresolvedKnowledge = List.of();
    turn.knowledgeStatus = ResultStatus.SKIPPED;
  }

  static boolean advance(Turn turn) {
    var blockedQuestions = new HashSet<String>();
    turn.evidenceBindings.stream()
        .filter(b -> b.required() && b.status() != GradeStatus.SUPPORTED)
        .forEach(b -> blockedQuestions.add(b.questionId()));
    for (int i = turn.evidenceCandidateIndex + 1; i < turn.activityCombinations.size(); i++) {
      var next = needs(turn, turn.activityCombinations.get(i).sessions());
      if (next.stream().anyMatch(n -> blockedQuestions.contains(n.questionId()))) continue;
      turn.evidenceCandidateIndex = i;
      reset(turn);
      return true;
    }
    return false;
  }
}
