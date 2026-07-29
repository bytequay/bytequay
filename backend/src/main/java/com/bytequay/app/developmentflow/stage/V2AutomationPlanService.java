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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.AcceptedApproval;
import com.bytequay.app.domain.Task;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/** Typed read/command boundary used by workspace-owned planning automation. */
@Component
public final class V2AutomationPlanService
{
    public static final String ISSUE_INTAKE = "remote-issue-intake";

    private final SqlitePlanRuntimeStore store;
    private final PlanRuntimeCoordinator coordinator;
    private final ObjectMapper json;

    public V2AutomationPlanService(
            SqlitePlanRuntimeStore store,
            PlanRuntimeCoordinator coordinator,
            ObjectMapper json)
    {
        this.store = requireNonNull(store, "store is null");
        this.coordinator = requireNonNull(coordinator, "coordinator is null");
        this.json = requireNonNull(json, "json is null");
    }

    public List<Snapshot> listCurrent(
            String workspaceId, String taskOrigin, String taskType)
    {
        return store.listAutomationPlans(workspaceId, taskOrigin, taskType).stream()
                .map(row -> new Snapshot(
                        row.taskId(), row.trunkId(), row.workspaceId(),
                        row.taskOrigin(), row.taskType(), row.linkedIssueNumber(),
                        row.taskCreatedAt(), row.taskEpoch(), row.taskVersion(),
                        row.stageId(), row.stageGeneration(), row.stageVersion(),
                        row.revisionId(), row.content(), row.selfReviewId(),
                        State.valueOf(row.state()), row.failureReason()))
                .toList();
    }

    /** Synchronously accepts one exact reviewed issue-intake Plan. */
    public AcceptedApproval approveIssueIntake(Snapshot expected)
    {
        requireNonNull(expected, "expected is null");
        if (expected.state() != State.REVIEWED
                || !Task.ORIGIN_ISSUE_MONITOR.equals(expected.taskOrigin())
                || !Task.TYPE_WORKSPACE_ISSUE_TRIAGE.equals(expected.taskType())
                || expected.linkedIssueNumber() == null) {
            throw new IllegalArgumentException(
                    "Automation approval requires a reviewed issue-intake Plan");
        }
        Snapshot current = listCurrent(
                expected.workspaceId(), expected.taskOrigin(), expected.taskType()).stream()
                .filter(candidate -> candidate.taskId().equals(expected.taskId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Issue-intake Plan is no longer current"));
        if (!current.equals(expected)) {
            throw new IllegalStateException(
                    "Issue-intake Plan changed after classification");
        }
        requireSafeIssueIntakePlan(current.content());
        if (store.hasOpenStewardship(current.revisionId())) {
            throw new IllegalStateException(
                    "Issue-intake Plan requires Project Stewardship review");
        }
        String actor = "automation/" + ISSUE_INTAKE;
        PlanRuntimeCoordinator.PlanApprovalCommand approval =
                new PlanRuntimeCoordinator.PlanApprovalCommand(
                        current.selfReviewId(), actor, current.taskId(),
                        current.taskEpoch(), current.taskVersion(),
                        current.stageId(), current.stageGeneration(),
                        current.stageVersion(), current.revisionId(),
                        current.selfReviewId());
        return coordinator.approvePlanByAutomation(
                new PlanRuntimeCoordinator.AutomationPlanApprovalCommand(
                        ISSUE_INTAKE, approval));
    }

    private void requireSafeIssueIntakePlan(String content)
    {
        JsonNode plan;
        try {
            plan = json.readTree(content);
        }
        catch (JsonProcessingException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Issue-intake Plan is not structured JSON", e);
        }
        JsonNode signals = plan.path("signals");
        if (!"finalized".equals(plan.path("status").asText())
                || !"high".equals(normalized(signals.path("confidence").asText()))
                || !"low".equals(normalized(signals.path("riskLevel").asText()))
                || !"small".equals(normalized(
                        signals.path("estimatedComplexity").asText()))) {
            throw new IllegalArgumentException(
                    "Issue-intake Plan is not high-confidence, low-risk, and small");
        }
    }

    private static String normalized(String value)
    {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public enum State
    {
        PENDING,
        REVIEWED,
        FAILED
    }

    public record Snapshot(
            String taskId,
            String trunkId,
            String workspaceId,
            String taskOrigin,
            String taskType,
            Integer linkedIssueNumber,
            Instant taskCreatedAt,
            long taskEpoch,
            long taskVersion,
            String stageId,
            Long stageGeneration,
            Long stageVersion,
            String revisionId,
            String content,
            String selfReviewId,
            State state,
            String failureReason) {}
}
