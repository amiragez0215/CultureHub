package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import cn.culturehub.AiGuideGraph.Preferences;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!v3-p5 & !v3-partner")
@Component
public class GuideIntent {
  public Requirements merge(Requirements previous, IntentPatch patch, Identity request, int gap) {
    return merge(previous, patch, request, gap, null);
  }

  public Requirements merge(
      Requirements previous, IntentPatch patch, Identity request, int gap, String previousVenue) {
    Problem.require(patch != null && patch.intentType() != null, "AI_OUTPUT_INVALID", "意图输出格式不完整");
    Preferences empty = new Preferences(null, null, null, null, null, null, null, null, null, null);
    Preferences old = previous == null ? empty : previous.hardConstraints();
    Preferences p = patch.constraints() == null ? empty : patch.constraints();
    for (String value :
        Arrays.asList(
            p.fromDate(),
            p.toDate(),
            p.fromTime(),
            p.toTime(),
            p.category(),
            p.audience(),
            p.keyword(),
            p.venue()))
      Problem.require(value == null || value.length() <= 120, "INVALID_PREFERENCES", "查询条件过长");
    String from = pick(p.fromDate(), old.fromDate()), to = pick(p.toDate(), old.toDate());
    String message = request.latestUserMessage();
    boolean replaceFamily = message.matches("(?s).*(不要|不找|不看|不选)亲子(活动|类)?[^。！？]*(改成|换成).*");
    String replacement = null;
    if (replaceFamily) {
      var matcher =
          java.util.regex.Pattern.compile("(?:改成|换成)\\s*([^，。！？；,.!?]{1,20})").matcher(message);
      if (matcher.find()) replacement = matcher.group(1).strip().replaceFirst("(?:活动|体验)$", "");
    }
    String venue = pick(p.venue(), old.venue());
    if (previousVenue != null
        && message.matches("(?s).*(还是|沿用|就选|保留).{0,12}(刚才|之前|原来|同一个).{0,8}(场馆|馆).*"))
      venue = previousVenue;
    LocalDate date = null;
    if (message.contains("今天")) date = request.currentTime().toLocalDate();
    if (message.contains("明天")) date = request.currentTime().toLocalDate().plusDays(1);
    if (message.contains("后天")) date = request.currentTime().toLocalDate().plusDays(2);
    if (message.contains("周六")) {
      LocalDate today = request.currentTime().toLocalDate();
      date =
          message.contains("下周")
              ? today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(12)
              : today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
    }
    if (date != null) {
      from = date.toString();
      to = from;
    }
    if (p.fromDate() != null && p.toDate() == null && !Objects.equals(p.fromDate(), old.fromDate()))
      to = from;
    Preferences hard =
        new Preferences(
            from,
            to,
            pick(p.fromTime(), old.fromTime()),
            pick(p.toTime(), old.toTime()),
            message.matches("(?s).*(类别不限|类型不限|不限类别|不限类型).*")
                ? null
                : replaceFamily && (p.category() == null || "FAMILY".equals(p.category()))
                    ? null
                    : pick(p.category(), old.category()),
            replaceFamily && (p.audience() == null || "FAMILY".equals(p.audience()))
                ? null
                : pick(p.audience(), old.audience()),
            replaceFamily ? pick(replacement, p.keyword()) : pick(p.keyword(), old.keyword()),
            message.contains("场馆不限") ? null : venue,
            p.childAge() == null ? old.childAge() : p.childAge(),
            p.minimumRemaining() == null ? old.minimumRemaining() : p.minimumRemaining());
    try {
      LocalDate start = from == null ? request.currentTime().toLocalDate() : LocalDate.parse(from);
      LocalDate end = to == null ? start.plusDays(30) : LocalDate.parse(to);
      Problem.require(
          !end.isBefore(start) && !end.isAfter(start.plusDays(90)),
          "INVALID_PREFERENCES",
          "日期范围必须在90天内");
      if (hard.fromTime() != null) LocalTime.parse(hard.fromTime());
      if (hard.toTime() != null) LocalTime.parse(hard.toTime());
      if (hard.fromTime() != null && hard.toTime() != null)
        Problem.require(
            LocalTime.parse(hard.fromTime()).isBefore(LocalTime.parse(hard.toTime())),
            "INVALID_PREFERENCES",
            "时间窗口无效");
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new Problem("INVALID_PREFERENCES", "日期或时间格式不正确", 400);
    }
    int count =
        patch.requestedActivityCount() == null
                || patch.requestedActivityCount() == 0 && !message.matches("(?s).*(0个活动|零个活动).*")
            ? (previous == null ? 1 : previous.requestedActivityCount())
            : patch.requestedActivityCount();
    int duration =
        patch.planningDurationMinutes() == null
                || patch.planningDurationMinutes() == 0 && !message.matches("(?s).*(0分钟|零分钟).*")
            ? (previous == null ? 1440 : previous.planningDurationMinutes())
            : patch.planningDurationMinutes();
    boolean explicitCount =
        message.matches(
            "(?s).*(((?<!第)[一二两三123]\\s*(个|场)\\s*(文化|非遗|亲子|艺术)?活动)|((必须|只要|改成|最好)[一二两三123]个)).*");
    if (previous != null && !explicitCount) count = previous.requestedActivityCount();
    if (previous != null && !message.matches("(?s).*(分钟|小时|时长|一天|一下午).*"))
      duration = previous.planningDurationMinutes();
    if (message.matches("(?s).*(总时间|总时长|整个行程|不超过|一共|时间预算|以内).*")) {
      var hours =
          java.util.regex.Pattern.compile("([0-9]{1,2}|[一二两三四五六七八九十])\\s*(?:个)?小时")
              .matcher(message);
      if (hours.find()) {
        String number = hours.group(1).replace('两', '二');
        duration =
            (number.matches("[0-9]+") ? Integer.parseInt(number) : "一二三四五六七八九十".indexOf(number) + 1)
                * 60;
      }
    }
    Problem.require(
        count >= 1 && count <= 3 && duration >= 1 && duration <= 1440,
        "INVALID_PREFERENCES",
        "仅支持1至3个活动及一天内的规划");
    Problem.require(
        hard.minimumRemaining() == null || hard.minimumRemaining() > 0,
        "INVALID_PREFERENCES",
        "最低余量必须为正数");
    Problem.require(
        hard.childAge() == null || hard.childAge() >= 0 && hard.childAge() <= 120,
        "INVALID_PREFERENCES",
        "年龄描述无效");
    var soft = new LinkedHashSet<String>();
    if (previous != null) soft.addAll(previous.softPreferences());
    if (patch.softPreferences() != null)
      soft.addAll(patch.softPreferences().stream().filter(Objects::nonNull).limit(10).toList());
    if (replaceFamily) soft.removeIf(pv -> pv.contains("亲子") || "FAMILY".equals(pv));
    boolean preferred =
        patch.countPreferred() == null
            ? previous != null && previous.countPreferred()
            : patch.countPreferred();
    if (previous != null && !explicitCount) preferred = previous.countPreferred();
    Integer revision = patch.revisionIndex();
    var ordinal = java.util.regex.Pattern.compile("第([一二三123])个").matcher(message);
    if (ordinal.find())
      revision = Map.of("一", 1, "二", 2, "三", 3, "1", 1, "2", 2, "3", 3).get(ordinal.group(1));
    else if (revision != null && revision == 0) revision = null;
    Problem.require(
        revision == null || revision >= 1 && revision <= 3, "INVALID_PREFERENCES", "修改位置应为第1至3个活动");
    return new Requirements(
        patch.intentType(),
        hard,
        List.copyOf(soft),
        patch.clarificationQuestion(),
        count,
        preferred,
        duration,
        gap,
        previous == null ? 0 : previous.previousPlanVersion(),
        revision,
        Boolean.TRUE.equals(patch.wordingOnly()));
  }

  private String pick(String next, String old) {
    return next == null || next.isBlank() ? old : next;
  }
}
