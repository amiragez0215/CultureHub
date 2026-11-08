package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;

/** Extracts only rule forms for which Java can check the meaning and all qualifiers. */
final class GuideRuleAssessment {
  private GuideRuleAssessment() {}

  private static final String DAYS = "一二三四五六日";
  private static final Pattern AGE =
      Pattern.compile("(\\d{1,3})\\s*(?:至|到|[-~～—–])\\s*(\\d{1,3})\\s*岁");
  private static final Pattern TIME =
      Pattern.compile("(\\d{2}:\\d{2})\\s*(?:至|到|[-~～—–])\\s*(\\d{2}:\\d{2})");
  private static final Pattern DAY = Pattern.compile("(?:周|星期|礼拜)([一二三四五六日天])");
  private static final Pattern DAY_RANGE =
      Pattern.compile("(?:周|星期)([一二三四五六日天])\\s*(?:至|到|[-~～—–])\\s*(?:周|星期)?([一二三四五六日天])");
  private static final Pattern ACCOMPANY =
      Pattern.compile(
          "(?<![无不免])(?:必须|须|需|要求|应由)(?:要|由|有)?(?:一名)?(?:家长|监护人|成人|成年人)(?:全程)?陪同|"
              + "(?:未有|没有|无)(?:家长|监护人|成人)(?:陪同)?不得(?:入场|参加)|"
              + "(?:不得|禁止|不允许)(?:儿童)?(?:单独|独自)(?:入场|参加)");
  private static final String GUARDIAN_REQUIRED = "须有家长或监护人陪同";
  private static final Pattern INDEPENDENT =
      Pattern.compile(
          "(?:不需要|无需|不用)(?:有|由)?(?:家长|监护人|成人)陪同|(?<![不未])(?:允许|可以|可)(?:(?:符合年龄要求的|[0-9]{1,3}\\s*(?:至|到|[-~～—–])\\s*[0-9]{1,3}\\s*岁)\\s*)?(?:儿童|孩子)?(?:单独|独自)(?:入场|参加)");

  static String day(String question) {
    var matcher = DAY.matcher(question);
    return matcher.find() ? "周" + matcher.group(1).replace('天', '日') : "";
  }

  static Optional<String> hours(String question, String text) {
    String day = day(question);
    for (String clause : text.split("[。；，;\\n]")) {
      if (clause.matches("(?s).*(节假日|临时|以公告|另行通知).*")) continue;
      if (!day.isEmpty() && !matchesDay(clause, day)) continue;
      if (!day.isEmpty() && clause.matches("(?s).*(闭馆|不开放|休馆).*") && !TIME.matcher(clause).find())
        return Optional.of(clause.strip());
      var times = TIME.matcher(clause);
      if (times.find() && (times.start() == 0 || clause.matches("(?s).*(开放|开馆|营业|周|星期|每日|每天).*"))) {
        try {
          if (LocalTime.parse(times.group(1)).isBefore(LocalTime.parse(times.group(2))))
            return Optional.of(clause.strip());
        } catch (java.time.format.DateTimeParseException invalid) {
          // The source did not state a usable time interval.
        }
      }
    }
    return Optional.empty();
  }

  private static boolean matchesDay(String clause, String day) {
    String normalized = clause.replace('天', '日');
    if (clause.contains("每日")
        || clause.contains("每天")
        || normalized.contains(day)
        || normalized.contains("星期" + day.charAt(1))
        || normalized.contains("礼拜" + day.charAt(1))) return true;
    var range = DAY_RANGE.matcher(normalized);
    if (!range.find()) return false;
    int target = DAYS.indexOf(day.charAt(1)),
        start = DAYS.indexOf(range.group(1)),
        end = DAYS.indexOf(range.group(2));
    return start <= end ? target >= start && target <= end : target >= start || target <= end;
  }

  static List<Claim> claims(RetrievalNeed need, Evidence evidence, Integer age) {
    String type = need.knowledgeType();
    var result = new ArrayList<Claim>();
    for (String raw : evidence.text().split("[。！？\\n]")) {
      String clause = raw.strip();
      if (clause.isEmpty() || clause.length() > 400 || clause.matches("(?s).*(除非|例外|特殊情况|另有规定).*"))
        continue;
      if (type.equals("AGE_RULE")) {
        var range = ageRange(clause);
        if (range == null) continue;
        boolean exclusive =
            clause.matches("(?s).*(参加|参与|报名|入场).*")
                && clause.matches("(?s).*(仅限|只限|只允许|仅允许|只接受|仅接受)(?:年龄在|年龄为|年满)?\\s*[0-9]{1,3}.*");
        boolean deny = clause.matches("(?s).*(?<!不)(禁止参加|不得参加|不允许参加|不可参加).*");
        boolean allow = clause.matches("(?s).*(可以参加|允许参加|可参加|可报名|可以报名).*");
        boolean recommended = clause.matches("(?s).*(推荐|建议|适合).*");
        boolean outside = age != null && (age < range[0] || age > range[1]);
        Conclusion conclusion;
        if (deny && !outside || exclusive && outside) conclusion = Conclusion.DENIED;
        else if ((allow || exclusive) && !deny && !outside) conclusion = Conclusion.ALLOWED;
        else if (recommended && !allow && !deny && !exclusive) conclusion = Conclusion.RECOMMENDED;
        else continue;
        String condition = ACCOMPANY.matcher(clause).find() ? GUARDIAN_REQUIRED : null;
        result.add(claim(need, evidence, conclusion, clause, condition, range));
      } else if (type.equals("GUARDIAN_RULE")) {
        var range = ageRange(clause);
        if (range == null && clause.matches("(?s).*\\d{1,3}\\s*(?:周)?岁.*")) continue;
        if (range != null && age != null && (age < range[0] || age > range[1])) continue;
        if (ACCOMPANY.matcher(clause).find())
          result.add(claim(need, evidence, Conclusion.ACCOMPANIMENT_REQUIRED, clause, null, range));
        if (INDEPENDENT.matcher(clause).find())
          result.add(
              claim(
                  need,
                  evidence,
                  Conclusion.INDEPENDENT_ALLOWED,
                  clause,
                  clause.contains("符合年龄要求") ? "须符合年龄要求" : null,
                  range));
      } else if (type.equals("OPENING_HOURS")) {
        hours(need.question(), clause)
            .ifPresent(
                value ->
                    result.add(
                        claim(
                            need,
                            evidence,
                            TIME.matcher(value).find() ? Conclusion.OPEN : Conclusion.CLOSED,
                            value,
                            null)));
      } else if (type.equals("ACTIVITY_BACKGROUND")
          && clause.matches("(?s).*(历史背景[：:]|起源于|源自|传统用途[：:]).{4,}")) {
        result.add(claim(need, evidence, Conclusion.EXCERPT, clause, null));
      } else if (type.equals("VENUE_RULE")
          && clause.matches("(?s).*参观规则[：:].{4,}")
          && (need.question().matches("(?s).*(参观规则|馆规|场馆规定).*")
              || !Collections.disjoint(terms(need.question()), terms(clause)))) {
        result.add(claim(need, evidence, Conclusion.EXCERPT, clause, null));
      } else if (type.equals("BACKGROUND")
          && clause.length() >= 10
          && !Collections.disjoint(terms(need.question()), terms(clause))) {
        result.add(claim(need, evidence, Conclusion.EXCERPT, clause, null));
      }
    }
    return List.copyOf(result);
  }

  private static int[] ageRange(String clause) {
    var range = AGE.matcher(clause);
    if (!range.find()) return null;
    int min = Integer.parseInt(range.group(1)), max = Integer.parseInt(range.group(2));
    return range.find() || min > max || max > 120 ? null : new int[] {min, max};
  }

  private static Claim claim(
      RetrievalNeed need, Evidence evidence, Conclusion verdict, String text, String condition) {
    return claim(need, evidence, verdict, text, condition, null);
  }

  private static Claim claim(
      RetrievalNeed need,
      Evidence evidence,
      Conclusion verdict,
      String text,
      String condition,
      int[] range) {
    return new Claim(
        need.questionId(),
        need.venueId(),
        need.activityId(),
        need.knowledgeType(),
        verdict,
        text,
        condition,
        evidence.chunkId(),
        range == null ? null : range[0],
        range == null ? null : range[1]);
  }

  static boolean conflict(List<Claim> claims, Integer age) {
    for (int i = 0; i < claims.size(); i++)
      for (int j = i + 1; j < claims.size(); j++) {
        Claim a = claims.get(i), b = claims.get(j);
        if (a.conclusion() == Conclusion.RECOMMENDED || b.conclusion() == Conclusion.RECOMMENDED)
          continue;
        if (age == null
            && a.minAge() != null
            && b.minAge() != null
            && (a.maxAge() < b.minAge() || b.maxAge() < a.minAge())) continue;
        if (a.conclusion() != b.conclusion()
            || a.conclusion() == Conclusion.OPEN
                && !timeRange(a.statement()).equals(timeRange(b.statement()))
            || a.condition() != null
                && b.condition() != null
                && !a.condition().equals(b.condition())) return true;
      }
    return false;
  }

  static RequirementVerdict verdict(
      RetrievalNeed need, GradeStatus status, List<Claim> claims, Integer age) {
    if (need.target() == RequirementTarget.NONE) return RequirementVerdict.NOT_APPLICABLE;
    if (status != GradeStatus.SUPPORTED) return RequirementVerdict.UNKNOWN;
    return switch (need.target()) {
      case AGE_ELIGIBILITY ->
          age == null
              ? RequirementVerdict.UNKNOWN
              : outcome(claims, Conclusion.ALLOWED, Conclusion.DENIED);
      case INDEPENDENT_ADMISSION ->
          outcome(
              claims.stream()
                  .filter(
                      c ->
                          (c.minAge() == null || age != null)
                              && !(c.conclusion() == Conclusion.INDEPENDENT_ALLOWED
                                  && c.condition() != null
                                  && c.minAge() == null))
                  .toList(),
              Conclusion.INDEPENDENT_ALLOWED,
              Conclusion.ACCOMPANIMENT_REQUIRED);
      case OPEN -> outcome(claims, Conclusion.OPEN, Conclusion.CLOSED);
      case NONE -> RequirementVerdict.NOT_APPLICABLE;
    };
  }

  private static RequirementVerdict outcome(List<Claim> claims, Conclusion yes, Conclusion no) {
    if (claims.stream().anyMatch(c -> c.conclusion() == no)) return RequirementVerdict.NOT_MATCHED;
    if (claims.stream().anyMatch(c -> c.conclusion() == yes)) return RequirementVerdict.MATCHED;
    return RequirementVerdict.UNKNOWN;
  }

  private static String timeRange(String text) {
    var time = TIME.matcher(text);
    return time.find() ? time.group(1) + "-" + time.group(2) : text;
  }

  static Set<String> terms(String text) {
    var terms = new HashSet<String>();
    for (var entry :
        Map.of(
                "opening", "开门|开放时间|开馆|营业|闭馆|休馆",
                "child", "儿童|少儿|孩子|岁|未成年|适龄",
                "guardian", "家长|监护人|陪同|独自|单独",
                "heritage", "历史|由来|起源|源自|传统用途",
                "checkin", "签到|核销|电子凭证",
                "registration", "预约|报名|候补|名额",
                "admission", "入场|进场|参观",
                "rules", "须知|规定|规则")
            .entrySet())
      if (text.matches("(?s).*(" + entry.getValue() + ").*")) terms.add(entry.getKey());
    String day = day(text);
    if (!day.isEmpty()) terms.add(day);
    return terms;
  }
}
