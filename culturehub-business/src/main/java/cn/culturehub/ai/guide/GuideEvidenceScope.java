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
    var questions =
        turn.knowledgeQuestions.isEmpty()
            ? GuideKnowledgeQuestions.plan(turn)
            : turn.knowledgeQuestions;
    var result = new ArrayList<RetrievalNeed>();
    var venues = turn.toolResults.stream().flatMap(r -> r.venues().stream()).toList();
    for (var question : questions) {
      if (turn.requirements.intentType() == IntentType.VENUE_QA) {
        if (venues.size() == 1) {
          var venue = venues.get(0);
          if (question.type().equals("OPENING_HOURS")
              && GuideRuleAssessment.hours(question.question(), venue.openingHours()).isPresent())
            continue;
          var venueId = question.type().equals("BACKGROUND") ? null : venue.venueId();
          result.add(need(question, venueId, null, venue.name()));
        } else if (question.type().equals("BACKGROUND")
            && turn.requirements.hardConstraints().venue() == null) {
          result.add(need(question, null, null, ""));
        }
        continue;
      }
      if (selected.isEmpty()) continue;
      if (question.activityScoped()) {
        for (var session : selected)
          result.add(need(question, session.venueId(), session.activityId(), session.title()));
      } else {
        var first = selected.get(0);
        result.add(need(question, first.venueId(), null, first.venueName()));
      }
    }
    return List.copyOf(result);
  }

  private static RetrievalNeed need(
      KnowledgeQuestion question, Long venue, Long activity, String name) {
    return new RetrievalNeed(
        question.id()
            + ":"
            + (venue == null ? "global" : venue)
            + (activity == null ? "" : ":" + activity),
        question.question() + (name.isBlank() ? "" : "（" + name + "）"),
        venue,
        activity,
        question.type(),
        question.required());
  }

  static List<Session> subjectSessions(Turn turn) {
    return turn.detailOnly ? turn.candidateSessions : selected(turn);
  }

  static boolean scoped(Evidence evidence, RetrievalNeed need) {
    String scope =
        need.activityId() != null ? "ACTIVITY" : need.venueId() == null ? "GLOBAL" : "VENUE";
    return (evidence.questionId() == null || need.questionId().equals(evidence.questionId()))
        && scope.equals(evidence.scope())
        && Objects.equals(evidence.venueId(), need.venueId())
        && Objects.equals(evidence.activityId(), need.activityId())
        && evidence.knowledgeType().equals(need.knowledgeType());
  }

  static List<EvidenceBinding> bind(Turn turn) {
    var selected = subjectSessions(turn);
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
      if (status == GradeStatus.SUPPORTED
          && (ids.isEmpty()
              || !scopedIds.containsAll(ids)
              || grade.claims() == null
              || grade.claims().isEmpty()
              || grade.claims().stream()
                  .anyMatch(
                      c ->
                          !claimMatches(c, need)
                              || !scopedIds.contains(c.evidenceId())
                              || !entails(turn, need, c)))) status = GradeStatus.INSUFFICIENT;
      if (selected.isEmpty()) {
        bindings.add(
            new EvidenceBinding(
                null,
                need.venueId(),
                need.activityId(),
                need.questionId(),
                need.required(),
                status,
                scopedIds,
                status == GradeStatus.SUPPORTED && grade.satisfiesRequirement()));
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
                  scopedIds,
                  status == GradeStatus.SUPPORTED && grade.satisfiesRequirement()));
        }
      }
    }
    return List.copyOf(bindings);
  }

  static boolean claimMatches(Claim claim, RetrievalNeed need) {
    return need.questionId().equals(claim.questionId())
        && Objects.equals(need.venueId(), claim.venueId())
        && Objects.equals(need.activityId(), claim.activityId())
        && need.knowledgeType().equals(claim.knowledgeType());
  }

  private static boolean entails(Turn turn, RetrievalNeed need, Claim claim) {
    Integer age = turn.requirements.hardConstraints().childAge();
    return turn.rerankedDocuments.stream()
        .filter(d -> claim.evidenceId().equals(d.chunkId()) && scoped(d, need))
        .anyMatch(d -> GuideRuleAssessment.claims(need, d, age).contains(claim));
  }

  static void reset(Turn turn) {
    turn.businessAnswers =
        turn.toolResults.stream()
            .flatMap(r -> r.venues().stream())
            .flatMap(
                v ->
                    turn.knowledgeQuestions.stream()
                        .filter(q -> q.type().equals("OPENING_HOURS"))
                        .flatMap(
                            q ->
                                GuideRuleAssessment.hours(q.question(), v.openingHours()).stream()
                                    .map(hours -> v.name() + "（场馆资料）：" + hours)))
            .toList();
    turn.retrievalNeeds = needs(turn, subjectSessions(turn));
    turn.retrievalPlan = List.of();
    turn.retrievedDocuments = List.of();
    turn.rerankedDocuments = List.of();
    turn.unavailableQuestions = new LinkedHashSet<>();
    turn.knowledgeGeneration = null;
    turn.evidenceGrade = List.of();
    turn.evidenceBindings = List.of();
    turn.supportedClaims = List.of();
    turn.unresolvedKnowledge = List.of();
    turn.knowledgeStatus = ResultStatus.SKIPPED;
  }

  static boolean advance(Turn turn) {
    var blockedQuestions = new HashSet<String>();
    turn.evidenceBindings.stream()
        .filter(b -> b.required() && !b.satisfiesRequirement())
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
