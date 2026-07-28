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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.developmentflow.compatibility.V2AgentRunProjection;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.userwait.V2UserWaitService;
import com.bytequay.app.domain.AgentQuestion;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.backlog.BacklogService;
import com.bytequay.app.service.question.AgentQuestionService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.ThreadService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestTrunkActivityService
{
    @Test
    void unionsTypedRunsQuestionsAndPermissionsWithoutLegacyWritesOrDuplicates()
    {
        ThreadService threads = mock(ThreadService.class);
        AgentQuestionService questions = mock(AgentQuestionService.class);
        NotificationService notifications = mock(NotificationService.class);
        AgentRunService runs = mock(AgentRunService.class);
        TaskStore tasks = mock(TaskStore.class);
        BacklogService backlog = mock(BacklogService.class);
        ReviewRoundStore reviews = mock(ReviewRoundStore.class);
        WorkspaceKnowledgeService knowledge = mock(WorkspaceKnowledgeService.class);
        V2AgentRunProjection v2Runs = mock(V2AgentRunProjection.class);
        V2UserWaitService v2Waits = mock(V2UserWaitService.class);
        Thread trunk = mock(Thread.class);
        when(trunk.workspaceId()).thenReturn("workspace-1");
        when(threads.find("trunk-1")).thenReturn(Optional.of(trunk));
        when(notifications.listForThread("trunk-1")).thenReturn(List.of());
        when(runs.findByThread("trunk-1")).thenReturn(List.of());
        when(tasks.listTasksByThread("trunk-1")).thenReturn(List.of());
        when(backlog.list("trunk-1")).thenReturn(List.of());
        when(knowledge.listRuns("workspace-1")).thenReturn(List.of());
        AgentRun typedRun = typedRun();
        when(v2Runs.listByTrunk("trunk-1")).thenReturn(List.of(
                typedRun, branchGuardRun()));
        AgentQuestion typedQuestion = question();
        when(v2Waits.listOpen("trunk-1")).thenReturn(List.of(typedQuestion));
        // A compatibility duplicate must not create two pinned cards.
        when(questions.listOpen("trunk-1")).thenReturn(List.of(typedQuestion));
        when(v2Waits.listOpenPermissions("trunk-1"))
                .thenReturn(List.of(permission()));
        TrunkActivityService service = new TrunkActivityService(
                threads, questions, notifications, runs, tasks, backlog,
                reviews, knowledge, v2Runs, v2Waits);

        var activity = service.get("trunk-1");

        assertThat(activity.costUsdMilli()).isEqualTo(27);
        assertThat(activity.timeline()).extracting(item -> item.id())
                .containsExactly("session:v2-ticket:ticket-1");
        assertThat(activity.pinned()).extracting(item -> item.id())
                .containsExactlyInAnyOrder(
                        "question:question-1", "permission:permission-1");
        assertThat(activity.pinned()).allMatch(item -> item.actionable());
    }

    private static AgentRun typedRun()
    {
        return new AgentRun(
                "v2-ticket:ticket-1", "task-1", AgentRun.KIND_DEV,
                AgentRun.SOURCE_LOCAL, "stage-1", null, "stage-1",
                AgentRun.STATUS_RUNNING, 1, null, "Implement", null,
                Instant.ofEpochMilli(10), null, "workspace-1", "trunk-1",
                "openai", "gpt-5.6", 27, 30, 40, 1,
                "Implement", null, null);
    }

    private static AgentRun branchGuardRun()
    {
        return new AgentRun(
                "v2-ticket:guard-ticket", "task-1",
                AgentRun.KIND_BRANCH_GUARD, AgentRun.SOURCE_REMOTE,
                "stage-1", null, "stage-1", AgentRun.STATUS_SUCCEEDED,
                1, null, "Repair branch", null, Instant.ofEpochMilli(9),
                Instant.ofEpochMilli(10), "workspace-1", "trunk-1",
                "openai", "gpt-5.6", 999, 30, 40, 1,
                "Repair branch", null, "completed");
    }

    private static AgentQuestion question()
    {
        return new AgentQuestion(
                "question-1", "trunk-1", "task-1", "question-call-1",
                "Choose an approach", "Context", List.of(), true,
                AgentQuestion.STATUS_OPEN, null, null,
                Instant.ofEpochMilli(11), null);
    }

    private static V2UserWaitStore.PermissionRequest permission()
    {
        return new V2UserWaitStore.PermissionRequest(
                "permission-1", "permission-call-1",
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.STAGE_TURN,
                        "turn-1", "operation-1"),
                "WRITE", "edit_file", "{}", "digest", "policy",
                "OPEN", null, 0, Instant.ofEpochMilli(12), null,
                null, null, null, null, 0, null, null,
                "WAITING", null, null);
    }
}
