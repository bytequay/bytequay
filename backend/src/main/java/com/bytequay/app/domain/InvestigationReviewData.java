/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.domain;

import com.bytequay.app.beans.localpr.PRCommentDto;
import com.bytequay.app.beans.localpr.PRTimelineEntryDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** Frozen AgentReview aggregate returned to every review surface. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InvestigationReviewData(
        AgentReviewRow review,
        List<ReviewRoundRow> rounds,
        List<AgentRun> runs,
        List<CriterionRow> criteria,
        List<ReviewObjectiveRow> objectives,
        List<ReviewAssignmentRow> assignments,
        List<HypothesisRow> hypotheses,
        List<InvestigationStepRow> steps,
        List<ObservationRow> observations,
        List<FindingRow> findings,
        List<FindingEvidenceRow> evidence,
        List<FindingVerificationRow> verifications,
        List<FindingRelationRow> relations,
        List<ReviewOutcomeRow> outcomes,
        List<KnowledgeItemRow> knowledgeItems,
        List<KnowledgeProvenanceRow> knowledgeProvenance,
        List<ActivityFactRow> activityFacts,
        List<PRCommentDto> prComments,
        List<PRTimelineEntryDto> prTimelineEvents)
{
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentReviewRow(
            String id, String repoId, String prId, String baseCommit,
            String reviewedHeadCommit, String status, String workspaceId,
            String ownerThreadId, String ownerTaskId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewRoundRow(
            String id, String reviewId, String agentRunId, String trigger,
            String scope, String startCommit, String endCommit, String status,
            RoundBudget budgetJson, int costCents, ReviewCapabilities capabilitiesJson,
            String triggerStageId)
    {
        public ReviewRoundRow(
                String id, String reviewId, String agentRunId, String trigger,
                String scope, String startCommit, String endCommit, String status,
                RoundBudget budgetJson, int costCents)
        {
            this(id, reviewId, agentRunId, trigger, scope, startCommit, endCommit,
                    status, budgetJson, costCents, ReviewCapabilities.remoteOnly(), null);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewCapabilities(
            String sourceMode, List<String> available, List<String> unavailable)
    {
        public static ReviewCapabilities localSource()
        {
            return new ReviewCapabilities(
                    "local-source",
                    List.of("pr_diff", "file_blobs", "repository_source",
                            "repository_callers", "git_history"),
                    List.of("code_graph", "local_tests"));
        }

        public static ReviewCapabilities remoteOnly()
        {
            return new ReviewCapabilities(
                    "remote-only",
                    List.of("pr_diff", "file_blobs", "commits", "checks"),
                    List.of("repository_callers", "code_graph", "local_tests", "git_history"));
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoundBudget(int costCapCents, int wallClockMinutes) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CriterionRow(
            String id, String repoId, String kind, String statement,
            String sourceType, String sourceRef) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewObjectiveRow(
            String id, String roundId, String criterionId, String statement,
            String source, String applicabilityStatus, String resolutionStatus) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewAssignmentRow(
            String id, String roundId, String reviewerDefId, String runner,
            String status, String understandingSummary, List<String> assumptionsJson,
            List<String> unknownsJson, AssignmentBudget budgetJson) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewerDefRow(
            String id, String name, String description, String runner,
            JsonNode runnerJson, String persona, List<String> eligibleKinds,
            boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AssignmentBudget(
            int hypotheses, int activeHypotheses, int steps, int findings) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HypothesisRow(
            String id, String assignmentId, String objectiveId, String claim,
            String origin, String status, String confidenceClass) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InvestigationStepRow(
            String id, String assignmentId, String hypothesisId, String actionType,
            JsonNode argumentsJson, String reason, boolean planned, int costCents,
            String status) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ObservationRow(
            String id, String stepId, String sourceType, String commitSha,
            String path, Integer startLine, Integer endLine, String symbol,
            String command, Integer exitCode, String artifactRef,
            String contentDigest, String preview) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FindingRow(
            String id, String reviewId, String roundId, String objectiveId,
            String hypothesisId, String criterionKind, String claim, int severity,
            String confidenceClass, String verificationStatus, String requestedAction,
            String lifecycleStatus, String lastCheckedCommit) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FindingEvidenceRow(
            String findingId, String observationId, String relation,
            String proposition, String strengthClass, String strengthReason,
            String dependencyMode, JsonNode dependencyJson) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FindingVerificationRow(
            String id, String findingId, String verifierRunId,
            boolean evidenceAccurate, boolean claimScopeAccurate,
            boolean severityAccurate, List<String> counterEvidenceJson,
            String status, String confidenceClass, String explanation) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FindingRelationRow(
            String sourceFindingId, String targetFindingId, String relation) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewOutcomeRow(
            String findingId, String userDisposition, String authorResponse,
            String epistemicResolution, String utilityAssessment,
            int styleEditMagnitude) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KnowledgeItemRow(
            String id, String repoId, String subtype, String statement,
            List<String> stepsJson, JsonNode triggerJson, String state) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KnowledgeProvenanceRow(
            String knowledgeItemId, String sourceKind, String sourceRef) {}

    /** Independent investigation activity only; never a correctness score. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActivityFactRow(String kind, long count, String detail) {}
}
