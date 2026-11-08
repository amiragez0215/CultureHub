package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import java.util.*;

/** Separates questions that need an answer from rules a recommendation must satisfy. */
final class GuideKnowledgeQuestions {
  private GuideKnowledgeQuestions() {}

  static List<KnowledgeQuestion> plan(Turn turn) {
    String text = turn.identity.latestUserMessage();
    boolean qa = turn.requirements.intentType() == IntentType.VENUE_QA && !turn.detailOnly;
    boolean consultation = qa || turn.detailOnly;
    var questions = new LinkedHashMap<String, KnowledgeQuestion>();
    if (turn.requirements.intentType() == IntentType.PLAN_REVISION)
      turn.previousKnowledgeQuestions.forEach(q -> questions.put(q.id(), q));

    Integer age = childAge(turn);
    boolean requiresAnswer = text.matches("(?s).*(必须确认|务必核实|需要确认).*");
    boolean guardian = text.matches("(?s).*(陪同|陪伴|监护人|(?:单独|独自)(?:入场|参加)).*");
    boolean independent = guardian && text.matches("(?s).*(单独|独自).*");
    boolean explicitAge =
        text.matches("(?s).*(年龄|几岁|适龄).*")
            || !guardian && text.matches("(?s).*(能否参加|可否参加|能不能参加|能参加吗|可以参加吗).*");
    boolean recommendation = text.matches("(?s).*(建议年龄|推荐年龄|推荐.{0,8}几岁|适合几岁).*");
    boolean activity =
        turn.detailOnly
            || !qa
                && (GuideTools.requestedSession(text) != null
                    || text.matches("(?s).*(剪纸|该活动|这项活动|该场次|活动的年龄|活动年龄).*"));
    boolean hardAge =
        !consultation
            && !recommendation
            && demands(
                text,
                "(?:满足|符合|适合|接受|允许).{0,18}(?:年龄|[0-9]{1,3}\\s*岁)|(?:年龄资格|年龄条件)(?:合格|达标|符合)|[0-9]{1,3}\\s*岁(?:儿童|孩子)?(?:可以|能|可)(?:参加|报名)");
    if (explicitAge || hardAge || age != null && !consultation && !guardian) {
      var old = questions.get("age");
      var target =
          consultation || recommendation
              ? RequirementTarget.NONE
              : hardAge
                  ? RequirementTarget.AGE_ELIGIBILITY
                  : old == null ? RequirementTarget.NONE : old.target();
      questions.put(
          "age",
          new KnowledgeQuestion(
              "age",
              (age == null ? "儿童" : age + "岁儿童") + (recommendation ? "的推荐年龄范围" : "的参与年龄范围及适用条件"),
              "AGE_RULE",
              consultation
                  || explicitAge
                  || requiresAnswer
                  || target != RequirementTarget.NONE
                  || old != null && old.required(),
              activity || old != null && old.activityScoped(),
              target));
    }

    boolean withGuardianAccepted =
        text.matches(
            "(?s).*(改为|改成|可以接受|我愿意|接受).{0,12}(?:家长|监护人|成人)?陪同.*|(?s).*(?:不再|不用|无需)要求.{0,12}(?:独自|单独).*");
    if (guardian) {
      var old = questions.get("guardian");
      boolean hardIndependent =
          independent
              && demands(text, "(?:(?:允许|可以|可|能|支持).{0,12})?(?:孩子|儿童|未成年人)?(?:单独|独自)(?:入场|参加)");
      var target =
          consultation || withGuardianAccepted
              ? RequirementTarget.NONE
              : hardIndependent
                  ? RequirementTarget.INDEPENDENT_ADMISSION
                  : old == null ? RequirementTarget.NONE : old.target();
      questions.put(
          "guardian",
          new KnowledgeQuestion(
              "guardian",
              independent && !withGuardianAccepted ? "儿童是否允许单独参加或入场" : "儿童入场是否要求监护人陪同",
              "GUARDIAN_RULE",
              consultation || target != RequirementTarget.NONE || requiresAnswer,
              activity,
              target));
    }

    if (text.matches("(?s).*(开门|开放时间|几点开|营业|闭馆|几点关|开馆).*")) {
      var target =
          !consultation && demands(text, "(?:周[一二三四五六日天])?(?:开门|开放|开馆|营业)")
              ? RequirementTarget.OPEN
              : RequirementTarget.NONE;
      questions.put(
          "opening",
          new KnowledgeQuestion(
              "opening",
              GuideRuleAssessment.day(text) + "场馆开放、闭馆时间",
              "OPENING_HOURS",
              consultation || target != RequirementTarget.NONE || requiresAnswer,
              false,
              target));
    }
    if (text.matches("(?s).*(背景|历史|由来|起源|来历).*"))
      questions.put(
          "background",
          new KnowledgeQuestion(
              "background",
              "活动历史背景、起源与传统用途",
              qa ? "BACKGROUND" : "ACTIVITY_BACKGROUND",
              false,
              !qa,
              RequirementTarget.NONE));
    if (questions.isEmpty() && qa)
      questions.put(
          "rules",
          new KnowledgeQuestion(
              "rules",
              text,
              turn.requirements.hardConstraints().venue() == null ? "BACKGROUND" : "VENUE_RULE",
              true,
              false,
              RequirementTarget.NONE));
    return List.copyOf(questions.values());
  }

  static Integer childAge(Turn turn) {
    if (turn.identity == null)
      return turn.requirements == null ? null : turn.requirements.hardConstraints().childAge();
    var mentions =
        java.util.regex.Pattern.compile("(?:(\\d{1,3})\\s*(?:至|到|[-~～—–])\\s*)?(\\d{1,3})\\s*岁")
            .matcher(turn.identity.latestUserMessage());
    var ages = new LinkedHashSet<Integer>();
    while (mentions.find()) {
      int age = Integer.parseInt(mentions.group(2));
      if (mentions.group(1) == null && age <= 120) ages.add(age);
    }
    if (ages.size() == 1) return ages.iterator().next();
    return turn.requirements == null ? null : turn.requirements.hardConstraints().childAge();
  }

  private static boolean demands(String text, String condition) {
    // A demand for an answer ("必须确认是否…") is separate from a demand for a positive outcome.
    String pattern =
        "(?s).*(?<![不非])(?:必须|只要|只找|只选|仅选|务必|硬性要求)"
            + "(?!\\s*(?:确认|核实|查询|知道|弄清))\\s*(?:找(?:一个|个)?|选(?:一个|个)?|安排(?:一个|个)?|是|让)?(?:的)?\\s*"
            + "(?:允许|可以|能|满足|符合)?\\s*(?:孩子|儿童|未成年人)?\\s*(?:"
            + condition
            + ").*";
    return Arrays.stream(text.split("[。！!？?；;，,]")).anyMatch(clause -> clause.matches(pattern));
  }
}
