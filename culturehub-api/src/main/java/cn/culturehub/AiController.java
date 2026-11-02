package cn.culturehub;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AiController {
  final AiService ai;
  final AiGuideGraph guide;
  final AiBookingProposalService proposals;

  public AiController(AiService ai, AiGuideGraph guide, AiBookingProposalService proposals) {
    this.ai = ai;
    this.guide = guide;
    this.proposals = proposals;
  }

  public record Message(@NotBlank @Size(max = 2000) String message) {}

  public record Selection(
      @NotBlank @Pattern(regexp = "[a-fA-F0-9-]{36}") String candidateId,
      @NotNull @Pattern(regexp = "DIRECT|WAITLIST") String mode) {}

  public record PlanInput(
      @JsonProperty("venue_id") @NotNull @Positive Long venueId,
      @NotBlank @Size(max = 500) String theme,
      @NotNull @Min(1) @Max(480) Integer minutes,
      @Pattern(regexp = "ALL|FAMILY|CHILDREN|YOUTH|ADULT|SENIOR") String audience,
      @Size(max = 2000) String requirements) {}

  public record ReportInput(
      @JsonProperty("session_id") @Positive Long sessionId,
      @JsonProperty("activity_id") @Positive Long activityId) {
    @AssertTrue
    public boolean isExactlyOneScope() {
      return (sessionId == null) != (activityId == null);
    }
  }

  @PostMapping("/ai/conversations")
  Object conversation() {
    return Map.of("id", guide.conversation());
  }

  @PostMapping("/ai/conversations/{id}/messages")
  Object message(@PathVariable String id, @Valid @RequestBody Message m) {
    return guide.message(id, m.message());
  }

  @PostMapping("/ai/conversations/{id}/proposals")
  Object select(@PathVariable String id, @Valid @RequestBody Selection selection) {
    return guide.select(id, selection.candidateId(), selection.mode());
  }

  @GetMapping("/ai/booking-proposals/{id}")
  Object proposal(@PathVariable String id) {
    return proposals.get(id);
  }

  @PostMapping("/ai/booking-proposals/{id}/confirm")
  ResponseEntity<?> confirm(@PathVariable String id) {
    var result = guide.confirm(id);
    String status = result.get("status").toString();
    return ResponseEntity.status(
            status.equals("PROCESSING")
                ? 202
                : Set.of("FAILED", "REJECTED", "EXPIRED").contains(status) ? 409 : 200)
        .body(result);
  }

  @PostMapping("/ai/booking-proposals/{id}/reject")
  Object reject(@PathVariable String id) {
    return proposals.reject(id);
  }

  @PostMapping("/admin/ai/activity-plans")
  Object plan(@Valid @RequestBody PlanInput input) {
    return ai.plan(
        Map.of(
            "venue_id",
            input.venueId(),
            "theme",
            input.theme(),
            "minutes",
            input.minutes(),
            "audience",
            input.audience() == null ? "ALL" : input.audience(),
            "requirements",
            input.requirements() == null ? "" : input.requirements()));
  }

  @PostMapping("/admin/ai/reports")
  Object report(@Valid @RequestBody ReportInput input) {
    return input.activityId() != null
        ? ai.activityReport(input.activityId())
        : ai.report(input.sessionId());
  }

  @GetMapping("/admin/ai/reports")
  Object latest(@RequestParam long sessionId) {
    return ai.latest(sessionId);
  }

  @PostMapping("/admin/ai/knowledge/reindex")
  Object reindex() throws Exception {
    return ai.reindex();
  }
}
