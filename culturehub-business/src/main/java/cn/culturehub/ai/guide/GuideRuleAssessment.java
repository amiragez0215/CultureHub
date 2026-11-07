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
  private static final Pattern INDEPENDENT =
      Pattern.compile("(?:不需要|无需|不用)(?:有|由)?(?:家长|监护人|成人)陪同|(?:允许|可以|可)(?:儿童)?(?:单独|独自)(?:入场|参加)");

  static String day(String question) {
    var matcher = DAY.matcher(question);
    return matcher.find() ? "周" + matcher.group(1).replace('天', '日') : "";
  }

  static Optional<String> hours(String question, String text) {
    String day = day(question);
    for (String clause : text.split("[。；\\n]")) {
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
        var range = AGE.matcher(clause);
        if (!range.find() || range.find()) continue;
        range.reset();
        range.find();
        int min = Integer.parseInt(range.group(1)), max = Integer.parseInt(range.group(2));
        if (min > max || max > 120) continue;
        boolean deny = clause.matches("(?s).*(禁止参加|不得参加|不允许参加|不可参加).*");
        boolean allow = clause.matches("(?s).*(可以参加|允许参加|可参加|适合|仅限).*");
        if (!allow && !deny || age != null && (age < min || age > max)) continue;
        String condition =
            ACCOMPANY.matcher(clause).find()
                ? "须有家长或监护人陪同"
                : INDEPENDENT.matcher(clause).find() ? "该条规则允许无监护人陪同" : null;
        result.add(
            new Claim(
                need.questionId(),
                need.venueId(),
                need.activityId(),
                type,
                deny ? Conclusion.DENIED : Conclusion.ALLOWED,
                clause,
                condition,
                evidence.chunkId(),
                min,
                max));
      } else if (type.equals("GUARDIAN_RULE")) {
        boolean requirement = ACCOMPANY.matcher(clause).find();
        if (requirement)
          result.add(claim(need, evidence, Conclusion.ACCOMPANIMENT_REQUIRED, clause, null));
        if (INDEPENDENT.matcher(clause).find())
          result.add(claim(need, evidence, Conclusion.INDEPENDENT_ALLOWED, clause, null));
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

  private static Claim claim(
      RetrievalNeed need, Evidence evidence, Conclusion verdict, String text, String condition) {
    return new Claim(
        need.questionId(),
        need.venueId(),
        need.activityId(),
        need.knowledgeType(),
        verdict,
        text,
        condition,
        evidence.chunkId(),
        null,
        null);
  }

  static boolean conflict(List<Claim> claims) {
    for (int i = 0; i < claims.size(); i++)
      for (int j = i + 1; j < claims.size(); j++) {
        Claim a = claims.get(i), b = claims.get(j);
        if (a.minAge() != null
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
