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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WorkspaceStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestWorkModelResolver
{
    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");
    private static final String WS_ID = "ws-default";
    private static final String THREAD_ID = "t-1";
    private static final String TASK_ID = "task-1";
    private static final UUID STAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final StageStore stageStore = mock(StageStore.class);

    private final WorkModelResolver resolver =
            new WorkModelResolverImpl(threadStore, taskStore, workspaceStore, stageStore);

    @Test
    void resolveForTaskPicksTheTaskOverrideWhenSet()
    {
        WorkModel taskPick = new WorkModel(WorkModelKind.API, "anthropic", "claude-opus-4-7", null);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(taskPick)));
        // The thread + workspace lookups should be skipped — task wins.

        WorkModelResolver.Resolved got = resolver.resolveForTask(THREAD_ID, TASK_ID);

        assertThat(got.choice()).isEqualTo(taskPick);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.TASK);
        assertThat(got.provenance().scopeId()).isEqualTo(TASK_ID);
    }

    @Test
    void resolveForTaskFallsThroughToThreadWhenTaskOverrideIsNull()
    {
        WorkModel threadPick = new WorkModel(WorkModelKind.CLI, "codex", null, null);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(null)));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(threadPick)));

        WorkModelResolver.Resolved got = resolver.resolveForTask(THREAD_ID, TASK_ID);

        assertThat(got.choice()).isEqualTo(threadPick);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.THREAD);
        assertThat(got.provenance().scopeId()).isEqualTo(THREAD_ID);
    }

    @Test
    void resolveForTaskFallsThroughToWorkspaceWhenThreadAndTaskAreEmpty()
    {
        WorkModel workspacePick = new WorkModel(WorkModelKind.API, "openai", "gpt-5", "team");
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(null)));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(workspacePick)));

        WorkModelResolver.Resolved got = resolver.resolveForTask(THREAD_ID, TASK_ID);

        assertThat(got.choice()).isEqualTo(workspacePick);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.WORKSPACE);
        assertThat(got.provenance().scopeId()).isEqualTo(WS_ID);
        assertThat(got.provenance().scopeLabel()).contains("ByteQuay");
    }

    @Test
    void resolveForTaskFallsThroughToGlobalDefaultWhenEveryScopeIsEmpty()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(null)));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));

        WorkModelResolver.Resolved got = resolver.resolveForTask(THREAD_ID, TASK_ID);

        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.GLOBAL_DEFAULT);
        // The catalog's first CLI agent is the v1 fallback. The exact
        // agent id is asserted via the catalog so reordering the
        // catalog flips the test rather than burying a hard-coded
        // "claude-code" here.
        WorkModelCatalog.CatalogAgent expected = WorkModelCatalog.CLI_AGENTS.get(0);
        assertThat(got.choice().kind()).isEqualTo(WorkModelKind.CLI);
        assertThat(got.choice().agentOrProvider()).isEqualTo(expected.id());
        assertThat(got.choice().model()).isEqualTo(expected.defaultModel().id());
        assertThat(got.choice().account()).isNull();
        assertThat(got.provenance().scopeId()).isNull();
    }

    @Test
    void resolveForThreadSkipsTheTaskScope()
    {
        // Even though a task with an override exists on this thread,
        // resolveForThread (trunk turn) must not pick it up — only
        // resolveForTask reads the task layer.
        WorkModel threadPick = new WorkModel(WorkModelKind.CLI, "claude-code", null, null);
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(threadPick)));

        WorkModelResolver.Resolved got = resolver.resolveForThread(THREAD_ID);

        assertThat(got.choice()).isEqualTo(threadPick);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.THREAD);
    }

    @Test
    void resolveForTaskRejectsTaskThatBelongsToAnotherThread()
    {
        Task otherThreadsTask = task(null);
        Task crossThread = new Task(
                otherThreadsTask.id(), "different-thread", otherThreadsTask.seq(),
                otherThreadsTask.status(),
                otherThreadsTask.branchName(), otherThreadsTask.worktreePath(),
                otherThreadsTask.baseBranch(), otherThreadsTask.workingDir(),
                otherThreadsTask.processPid(), otherThreadsTask.logPath(),
                otherThreadsTask.prNumber(), otherThreadsTask.prState(), otherThreadsTask.ciState(),
                otherThreadsTask.taskType(), otherThreadsTask.linkedPrNumber(),
                otherThreadsTask.linkedIssueNumber(),
                otherThreadsTask.costUsdMilli(), otherThreadsTask.tokensIn(), otherThreadsTask.tokensOut(),
                otherThreadsTask.agentSessionId(),
                otherThreadsTask.createdAt(), otherThreadsTask.endedAt(), otherThreadsTask.errorMessage(),
                otherThreadsTask.name(), otherThreadsTask.roleSkill(), otherThreadsTask.workModel());
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(crossThread));

        assertThatThrownBy(() -> resolver.resolveForTask(THREAD_ID, TASK_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not on thread");
    }

    @Test
    void resolveForThreadRejectsUnknownThread()
    {
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveForThread(THREAD_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no thread");
    }

    @Test
    void resolveForStagePicksTheStageOverrideWhenSet()
    {
        WorkModel stagePick = new WorkModel(WorkModelKind.API, "anthropic", "claude-opus-4-7", null);
        when(stageStore.findStageById(STAGE_ID)).thenReturn(Optional.of(stage(stagePick)));
        // The task + thread + workspace lookups should be skipped — stage wins.

        WorkModelResolver.Resolved got = resolver.resolveForStage(THREAD_ID, TASK_ID, STAGE_ID.toString());

        assertThat(got.choice()).isEqualTo(stagePick);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.STAGE);
        assertThat(got.provenance().scopeId()).isEqualTo(STAGE_ID.toString());
    }

    @Test
    void resolveForStageFallsThroughToTaskWhenStageOverrideIsNull()
    {
        WorkModel taskPick = new WorkModel(WorkModelKind.API, "anthropic", "claude-opus-4-7", null);
        when(stageStore.findStageById(STAGE_ID)).thenReturn(Optional.of(stage(null)));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(taskPick)));

        WorkModelResolver.Resolved got = resolver.resolveForStage(THREAD_ID, TASK_ID, STAGE_ID.toString());

        assertThat(got.choice()).isEqualTo(taskPick);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.TASK);
    }

    @Test
    void resolveForStageRejectsStageThatBelongsToAnotherTask()
    {
        when(stageStore.findStageById(STAGE_ID))
                .thenReturn(Optional.of(new StageInstance(
                        STAGE_ID, "different-task", StageType.DEVELOPMENT_STAGE, StageState.OPEN,
                        NOW, null, null, null)));

        assertThatThrownBy(() -> resolver.resolveForStage(THREAD_ID, TASK_ID, STAGE_ID.toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not on task");
    }

    @Test
    void resolveForStageRejectsUnknownStage()
    {
        when(stageStore.findStageById(STAGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveForStage(THREAD_ID, TASK_ID, STAGE_ID.toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no stage");
    }

    private static StageInstance stage(WorkModel workModel)
    {
        return new StageInstance(
                STAGE_ID, TASK_ID, StageType.DEVELOPMENT_STAGE, StageState.OPEN,
                NOW, null, null, workModel);
    }

    private static Thread thread(WorkModel workModel)
    {
        return new Thread(
                THREAD_ID, ThreadKind.CLI_AGENT, "claude-code", null,
                "Resolver fixture", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, WS_ID, workModel, null);
    }

    private static Task task(WorkModel workModel)
    {
        return new Task(
                TASK_ID, THREAD_ID, 1L, TaskStatus.IDLE,
                "feature/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, workModel);
    }

    private static Workspace workspace(WorkModel workModel)
    {
        return new Workspace(WS_ID, "ByteQuay", "", false, workModel, NOW, NOW);
    }
}
