package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import java.util.*;

/** Separates rule questions before resolving their catalog scope. */
final class GuideKnowledgeQuestions {
  private GuideKnowledgeQuestions() {}

  static List<KnowledgeQuestion> plan(Turn turn) {
    String text = turn.identity.latestUserMessage();
    boolean qa = turn.requirements.intentType() == IntentType.VENUE_QA;
    var questions = new LinkedHashMap<String, KnowledgeQuestion>();
    if (turn.requirements.intentType() == IntentType.PLAN_REVISION)
      turn.previousKnowledgeQuestions.forEach(q -> questions.put(q.id(), q));
    Integer age = turn.requirements.hardConstraints().childAge();
    boolean explicitAge = text.matches("(?s).*(年龄|几岁|能否参加|可否参加|能不能参加|可以参加吗|适龄).*");
    boolean explicit = text.matches("(?s).*(必须|确认|能否|能不能|可否|允许|是否|可以参加吗).*");
    boolean activity =
        !qa
            && (GuideTools.requestedSession(text) != null
                || text.matches("(?s).*(剪纸|该活动|这项活动|该场次|活动的年龄|活动年龄).*"));
    if (age != null || explicitAge)
      questions.put(
          "age",
          new KnowledgeQuestion(
              "age",
              (age == null ? "儿童" : age + "岁儿童") + "的参与年龄范围及适用条件",
              "AGE_RULE",
              qa || explicitAge || explicit || required(questions, "age"),
              activity));
    if (text.matches("(?s).*(陪同|陪伴|监护人|单独入场|独自入场|独自参加|单独参加).*")) {
      boolean independent = text.matches("(?s).*(单独|独自).*");
      questions.put(
          "guardian",
          new KnowledgeQuestion(
              "guardian",
              independent ? "儿童是否允许单独参加或入场" : "儿童入场是否要求监护人陪同",
              "GUARDIAN_RULE",
              true,
              false));
    }
    if (text.matches("(?s).*(开门|开放时间|几点开|营业|闭馆|几点关|开馆).*"))
      questions.put(
          "opening",
          new KnowledgeQuestion(
              "opening",
              GuideRuleAssessment.day(text) + "场馆开放、闭馆时间",
              "OPENING_HOURS",
              qa || explicit,
              false));
    if (text.matches("(?s).*(背景|历史|由来|起源|来历).*"))
      questions.put(
          "background",
          new KnowledgeQuestion(
              "background",
              "活动历史背景、起源与传统用途",
              qa ? "BACKGROUND" : "ACTIVITY_BACKGROUND",
              false,
              !qa));
    if (questions.isEmpty() && qa)
      questions.put(
          "rules",
          new KnowledgeQuestion(
              "rules",
              text,
              turn.requirements.hardConstraints().venue() == null ? "BACKGROUND" : "VENUE_RULE",
              true,
              false));
    return List.copyOf(questions.values());
  }

  private static boolean required(Map<String, KnowledgeQuestion> questions, String id) {
    return questions.containsKey(id) && questions.get(id).required();
  }
}
