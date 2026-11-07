package cn.culturehub.ai.guide;

import cn.culturehub.AiGuideGraph.Preferences;
import java.time.*;
import java.util.*;

/** Typed domain protocol; the Graph carrier has exactly one domain value, never model identity. */
public final class GuideState {
  private GuideState() {}

  public enum IntentType {
    VENUE_QA,
    ACTIVITY_SEARCH,
    ACTIVITY_PLAN,
    PLAN_REVISION,
    BOOKING_REQUEST
  }

  public enum ResultStatus {
    SUCCESS,
    EMPTY,
    PARTIAL,
    FAILED,
    SKIPPED
  }

  public enum ToolName {
    SEARCH_SESSIONS,
    SESSION_DETAILS,
    VENUE_INFORMATION,
    MY_ACTIVE_BOOKINGS
  }

  public enum Failure {
    NONE,
    TOOL_EXECUTION_FAILED,
    TOOL_PLAN_INCOMPLETE,
    MISSING_BUSINESS_DATA,
    RETRIEVAL_INSUFFICIENT,
    EVIDENCE_CONFLICT,
    NO_VALID_COMBINATION,
    OUTPUT_FORMAT_INVALID,
    FACT_MISMATCH,
    SCHEDULE_CONFLICT,
    HARD_CONSTRAINT_CONFLICT,
    AUTHORIZATION_REQUIRED
  }

  public record Identity(
      String conversationId,
      String turnId,
      String traceId,
      long ownerUserId,
      String latestUserMessage,
      LocalDateTime currentTime)
      implements java.io.Serializable {}

  public record Requirements(
      IntentType intentType,
      Preferences hardConstraints,
      List<String> softPreferences,
      String clarificationQuestion,
      int requestedActivityCount,
      boolean countPreferred,
      int planningDurationMinutes,
      int minimumGapMinutes,
      int previousPlanVersion,
      Integer revisionIndex,
      boolean wordingOnly)
      implements java.io.Serializable {}

  /** Model patch is separate from authoritative identity, tool results and persistent state. */
  public record IntentPatch(
      IntentType intentType,
      Preferences constraints,
      List<String> softPreferences,
      String clarificationQuestion,
      Integer requestedActivityCount,
      Boolean countPreferred,
      Integer planningDurationMinutes,
      Integer revisionIndex,
      Boolean wordingOnly)
      implements java.io.Serializable {}

  public record Need(String id, String question, String dependency, boolean required)
      implements java.io.Serializable {}

  public record ToolCall(ToolName name, Long sessionId, String keyword)
      implements java.io.Serializable {}

  public record Session(
      long sessionId,
      long activityId,
      long venueId,
      String title,
      String category,
      String audience,
      String description,
      String venueName,
      LocalDateTime startAt,
      LocalDateTime endAt,
      LocalDateTime registrationStartAt,
      LocalDateTime registrationEndAt,
      int remaining,
      boolean waitlistPriority,
      boolean registrationOpen,
      LocalDateTime asOf)
      implements java.io.Serializable {}

  public record ExistingBooking(
      long participationId,
      long sessionId,
      String status,
      LocalDateTime startAt,
      LocalDateTime endAt)
      implements java.io.Serializable {}

  public record Venue(long venueId, String name, String introduction, String openingHours)
      implements java.io.Serializable {}

  public record ToolResult(
      ToolName tool,
      ResultStatus status,
      List<Session> sessions,
      List<ExistingBooking> bookings,
      List<Venue> venues,
      LocalDateTime queriedAt,
      String errorType)
      implements java.io.Serializable {}

  public record ToolCheckResult(ResultStatus status, Failure failureType, List<String> missing)
      implements java.io.Serializable {}

  public record RetrievalNeed(
      String questionId,
      String question,
      Long venueId,
      Long activityId,
      String knowledgeType,
      boolean required)
      implements java.io.Serializable {}

  public record RetrievalQuery(RetrievalNeed need, String rewrittenQuery, int topK)
      implements java.io.Serializable {}

  public record Evidence(
      String documentId,
      String chunkId,
      Long venueId,
      Long activityId,
      String scope,
      String source,
      String knowledgeType,
      String version,
      LocalDate expiresOn,
      String text,
      double retrievalScore,
      double rerankScore)
      implements java.io.Serializable {}

  public enum GradeStatus {
    SUPPORTED,
    INSUFFICIENT,
    CONFLICTING,
    UNAVAILABLE
  }

  public record Grade(
      String questionId,
      GradeStatus status,
      List<String> supportedFacts,
      List<String> evidenceIds,
      List<String> unresolvedQuestions,
      String failureReason)
      implements java.io.Serializable {}

  public record EvidenceBinding(
      Long sessionId,
      Long venueId,
      Long activityId,
      String questionId,
      boolean required,
      GradeStatus status,
      List<String> evidenceIds)
      implements java.io.Serializable {}

  public record Combination(List<Session> sessions, double score, List<String> reasons)
      implements java.io.Serializable {}

  public record ScheduleItem(long sessionId, LocalDateTime startAt, LocalDateTime endAt)
      implements java.io.Serializable {}

  public record Proposal(
      String planId,
      int planVersion,
      String title,
      List<Long> orderedSessionIds,
      List<ScheduleItem> scheduleItems,
      List<String> recommendationReasons,
      List<String> evidenceReferences,
      List<String> unresolvedConditions,
      List<String> warnings,
      LocalDateTime generatedAt)
      implements java.io.Serializable {}

  public record Violation(
      Failure errorType,
      String affectedField,
      List<Long> affectedSessionIds,
      String failureReason,
      String repairTarget,
      boolean retryable)
      implements java.io.Serializable {}

  public record Verification(boolean passed, List<Violation> errors)
      implements java.io.Serializable {}

  public static class Turn implements java.io.Serializable {
    public Proposal previousPlan;
    public boolean reuseBusinessFacts;
    public Identity identity;
    public Requirements requirements;
    public List<Need> informationNeeds = new ArrayList<>();
    public List<ToolCall> toolPlan = new ArrayList<>();
    public List<ToolResult> toolResults = new ArrayList<>();
    public ToolCheckResult toolCheckResult;
    public List<Session> candidateSessions = new ArrayList<>();
    public List<ExistingBooking> existingBookings = new ArrayList<>();
    public List<RetrievalNeed> retrievalNeeds = new ArrayList<>();
    public List<RetrievalQuery> retrievalPlan = new ArrayList<>();
    public List<Evidence> retrievedDocuments = new ArrayList<>(),
        rerankedDocuments = new ArrayList<>();
    public List<Grade> evidenceGrade = new ArrayList<>();
    public List<EvidenceBinding> evidenceBindings = new ArrayList<>();
    public int evidenceCandidateIndex;
    public List<String> supportedClaims = new ArrayList<>(),
        unresolvedKnowledge = new ArrayList<>();
    public List<Session> rankedCandidates = new ArrayList<>();
    public List<Combination> activityCombinations = new ArrayList<>();
    public Proposal proposal;
    public Verification verificationResult;
    public Failure failureType = Failure.NONE;
    public String repairTarget, phase = "START", currentNode;
    public Map<String, Integer> repairCounters = new LinkedHashMap<>();
    public List<String> visitedNodes = new ArrayList<>();
    public boolean cancelled;
    public int toolCalls, llmCalls, currentPlanVersion;
    public long promptTokens, completionTokens;
    public ResultStatus knowledgeStatus = ResultStatus.SKIPPED;
    public long deadlineEpochMillis;
  }
}
