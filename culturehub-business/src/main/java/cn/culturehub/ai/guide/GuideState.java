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

  public record ConversationExchange(String user, String assistant)
      implements java.io.Serializable {}

  public record Need(
      String id,
      String question,
      String dependency,
      boolean required,
      ToolName tool,
      ToolArguments arguments)
      implements java.io.Serializable {
    public Need(String id, String question, String dependency, boolean required) {
      this(id, question, dependency, required, null, null);
    }
  }

  public record ToolArguments(Long sessionId, String keyword, Preferences filters)
      implements java.io.Serializable {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record ToolCall(
      ToolName name,
      ToolArguments arguments,
      String informationNeedId,
      boolean required,
      String dependency)
      implements java.io.Serializable {
    public ToolCall(ToolName name, Long sessionId, String keyword) {
      this(name, new ToolArguments(sessionId, keyword, null), name.name(), true, null);
    }

    public Long sessionId() {
      return arguments.sessionId();
    }

    public String keyword() {
      return arguments.keyword();
    }
  }

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
      String errorType,
      String informationNeedId,
      ToolArguments arguments)
      implements java.io.Serializable {
    public ToolResult(
        ToolName tool,
        ResultStatus status,
        List<Session> sessions,
        List<ExistingBooking> bookings,
        List<Venue> venues,
        LocalDateTime queriedAt,
        String errorType) {
      this(tool, status, sessions, bookings, venues, queriedAt, errorType, tool.name(), null);
    }

    ToolResult forCall(ToolCall call) {
      return new ToolResult(
          tool,
          status,
          sessions,
          bookings,
          venues,
          queriedAt,
          errorType,
          call.informationNeedId(),
          call.arguments());
    }
  }

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

  public record KnowledgeQuestion(
      String id, String question, String type, boolean required, boolean activityScoped)
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
      double rerankScore,
      String questionId)
      implements java.io.Serializable {
    public Evidence(
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
        double rerankScore) {
      this(
          documentId,
          chunkId,
          venueId,
          activityId,
          scope,
          source,
          knowledgeType,
          version,
          expiresOn,
          text,
          retrievalScore,
          rerankScore,
          null);
    }
  }

  public enum GradeStatus {
    SUPPORTED,
    INSUFFICIENT,
    CONFLICTING,
    UNAVAILABLE
  }

  public enum Conclusion {
    ALLOWED,
    DENIED,
    ACCOMPANIMENT_REQUIRED,
    INDEPENDENT_ALLOWED,
    OPEN,
    CLOSED,
    EXCERPT
  }

  public record Claim(
      String questionId,
      Long venueId,
      Long activityId,
      String knowledgeType,
      Conclusion conclusion,
      String statement,
      String condition,
      String evidenceId,
      Integer minAge,
      Integer maxAge)
      implements java.io.Serializable {}

  public record Grade(
      String questionId,
      GradeStatus status,
      List<String> supportedFacts,
      List<String> evidenceIds,
      List<String> unresolvedQuestions,
      String failureReason,
      List<Claim> claims,
      boolean satisfiesRequirement)
      implements java.io.Serializable {
    public Grade(
        String questionId,
        GradeStatus status,
        List<String> supportedFacts,
        List<String> evidenceIds,
        List<String> unresolvedQuestions,
        String failureReason) {
      this(
          questionId,
          status,
          supportedFacts,
          evidenceIds,
          unresolvedQuestions,
          failureReason,
          List.of(),
          status == GradeStatus.SUPPORTED);
    }
  }

  public record EvidenceBinding(
      Long sessionId,
      Long venueId,
      Long activityId,
      String questionId,
      boolean required,
      GradeStatus status,
      List<String> evidenceIds,
      boolean satisfiesRequirement)
      implements java.io.Serializable {}

  public record Combination(List<Session> sessions, double score, List<String> reasons)
      implements java.io.Serializable {}

  public record ScheduleItem(long sessionId, LocalDateTime startAt, LocalDateTime endAt)
      implements java.io.Serializable {}

  public record RecommendationFact(String factId, Long sessionId, String statement)
      implements java.io.Serializable {}

  public enum NarrationStyle {
    DIRECT,
    EXPLAIN
  }

  public record RecommendationRef(
      String factId, Long sessionId, String questionId, String evidenceId, NarrationStyle style)
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
      LocalDateTime generatedAt,
      List<RecommendationRef> recommendationRefs)
      implements java.io.Serializable {
    public Proposal {
      if (recommendationRefs == null) recommendationRefs = List.of();
    }

    public Proposal(
        String planId,
        int planVersion,
        String title,
        List<Long> orderedSessionIds,
        List<ScheduleItem> scheduleItems,
        List<String> recommendationReasons,
        List<String> evidenceReferences,
        List<String> unresolvedConditions,
        List<String> warnings,
        LocalDateTime generatedAt) {
      this(
          planId,
          planVersion,
          title,
          orderedSessionIds,
          scheduleItems,
          recommendationReasons,
          evidenceReferences,
          unresolvedConditions,
          warnings,
          generatedAt,
          List.of());
    }
  }

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
    public String previousVenue;
    public List<ConversationExchange> recentMessages = List.of();
    public boolean reuseBusinessFacts;
    public Identity identity;
    public Requirements requirements;
    public List<Need> informationNeeds = new ArrayList<>();
    public List<KnowledgeQuestion> knowledgeQuestions = new ArrayList<>();
    public List<KnowledgeQuestion> previousKnowledgeQuestions = new ArrayList<>();
    public List<String> businessAnswers = new ArrayList<>();
    public List<ToolCall> toolPlan = new ArrayList<>();
    public List<ToolResult> toolResults = new ArrayList<>();
    public ToolCheckResult toolCheckResult;
    public List<Session> candidateSessions = new ArrayList<>();
    public List<ExistingBooking> existingBookings = new ArrayList<>();
    public List<RetrievalNeed> retrievalNeeds = new ArrayList<>();
    public List<RetrievalQuery> retrievalPlan = new ArrayList<>();
    public List<Evidence> retrievedDocuments = new ArrayList<>(),
        rerankedDocuments = new ArrayList<>();
    public Set<String> unavailableQuestions = new LinkedHashSet<>();
    public String knowledgeGeneration;
    public List<Grade> evidenceGrade = new ArrayList<>();
    public List<EvidenceBinding> evidenceBindings = new ArrayList<>();
    public int evidenceCandidateIndex;
    public List<String> supportedClaims = new ArrayList<>(),
        unresolvedKnowledge = new ArrayList<>();
    public List<Session> rankedCandidates = new ArrayList<>();
    public List<Combination> activityCombinations = new ArrayList<>();
    public List<RecommendationFact> recommendationFacts = new ArrayList<>();
    public Proposal proposal;
    public Verification verificationResult;
    public Failure failureType = Failure.NONE;
    public String repairTarget, phase = "START", currentNode;
    public Map<String, Integer> repairCounters = new LinkedHashMap<>();
    public List<String> visitedNodes = new ArrayList<>();
    public boolean cancelled;
    public boolean detailOnly;
    public int toolCalls, llmCalls, nativeToolCalls, currentPlanVersion;
    public long promptTokens, completionTokens;
    public ResultStatus knowledgeStatus = ResultStatus.SKIPPED;
    public long deadlineEpochMillis;
  }
}
