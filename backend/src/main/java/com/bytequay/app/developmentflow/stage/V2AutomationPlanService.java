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
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Typed read boundary used by workspace-owned planning automation.
 *
 * <p>Read-only by design. It used to expose {@code approveIssueIntake}, which
 * started a writer agent when a Plan reported itself high-confidence, low-risk
 * and small — but those signals are authored by an agent whose only input is
 * GitHub issue text that any account can write, so the decision to execute code
 * belonged to the issue reporter. Triaged issues now always ask the user.
 */
@Component
public final class V2AutomationPlanService
{
    public static final String ISSUE_INTAKE = "remote-issue-intake";

    private final SqlitePlanRuntimeStore store;

    public V2AutomationPlanService(SqlitePlanRuntimeStore store)
    {
        this.store = requireNonNull(store, "store is null");
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
