package cn.culturehub.ai.guide;

import static org.junit.jupiter.api.Assertions.*;

import cn.culturehub.ai.guide.GuideState.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GuideChallengeTest {
  @Test
  void repeatableDeterministicChallengeSet() throws Exception {
    var json = new ObjectMapper();
    var fixture = new GuidePlanningTest();
    var results = new ArrayList<Map<String, Object>>();
    var cases = json.readTree(getClass().getResourceAsStream("/ai-v2-challenges.json"));
    for (var test : cases) {
      var state = fixture.state(test.get("count").asInt(), test.get("preferred").asBoolean());
      long id = 1;
      for (var window : test.get("windows"))
        state.candidateSessions.add(
            fixture.session(id++, window.get(0).asInt(), window.get(1).asInt()));
      if (test.path("existingConflict").asBoolean())
        state.existingBookings =
            List.of(
                new ExistingBooking(
                    1,
                    9,
                    "RESERVED",
                    GuidePlanningTest.NOW.withHour(10),
                    GuidePlanningTest.NOW.withHour(11)));
      long started = System.nanoTime();
      var choices = fixture.planner.optimize(state);
      long elapsed = System.nanoTime() - started;
      int count = choices.isEmpty() ? 0 : choices.get(0).sessions().size();
      assertEquals(test.get("expectedCount").asInt(), count, test.get("id").asText());
      results.add(
          Map.of(
              "case",
              test.get("id").asText(),
              "passed",
              true,
              "expectedCount",
              test.get("expectedCount").asInt(),
              "actualCount",
              count,
              "optimizerNanos",
              elapsed));
    }
    Files.writeString(
        Path.of("target/ai-v2-challenge-results.json"),
        json.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "evaluationType",
                    "DETERMINISTIC_JAVA_NOT_MODEL_QUALITY",
                    "v1Measured",
                    false,
                    "realModel",
                    false,
                    "llmCalls",
                    0,
                    "tokenCostMeasured",
                    false,
                    "results",
                    results)));
  }
}
