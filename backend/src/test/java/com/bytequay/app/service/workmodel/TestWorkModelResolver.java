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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * New trunks freeze the workspace's effective engines at creation; a task
 * or stage can only dial reasoning effort. These tests pin both halves and
 * the audience each scope resolves under, including child brain sessions.
 */
class TestWorkModelResolver
{
    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");
    private static final String WS_ID = "ws-default";
    private static final String THREAD_ID = "t-1";
    private static final String TASK_ID = "task-1";
    private static final UUID STAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final WorkModel CODEX = new WorkModel(WorkModelKind.CLI, "codex", null, null);

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final WorkspaceEngineSettings engineSettings = mock(WorkspaceEngineSettings.class);
    private final ThreadEngineOverrides threadEngines = mock(ThreadEngineOverrides.class);

    private final WorkModelResolver resolver = new WorkModelResolver(
            threadStore, taskStore, workspaceStore, stageStore, engineSettings, threadEngines);

    @Test
    void theEngineComesFromTheWorkspaceRowForTheSessionsAudience()
    {
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        // A task turn is development work, so the "dev" row applies.
        when(engineSettings.forAudience(WS_ID, "dev"))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(CODEX, true)));

        WorkModelResolver.Resolved got = resolver.resolveForTask(THREAD_ID, TASK_ID);

        assertThat(got.choice()).isEqualTo(CODEX);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.WORKSPACE);
        assertThat(got.provenance().scopeId()).isEqualTo(WS_ID);
        assertThat(got.provenance().scopeLabel()).isEqualTo("workspace ByteQuay · dev");
    }

    @Test
    void aTrunkTurnResolvesUnderThePlanningRow()
    {
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        when(engineSettings.forAudience(WS_ID, "plan"))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(CODEX, true)));

        assertThat(resolver.resolveForThread(THREAD_ID).choice()).isEqualTo(CODEX);
    }

    @Test
    void aTaskBrainUsesTheParentSnapshotCopiedOntoItsChildThread()
    {
        WorkModel frozen = new WorkModel(
                WorkModelKind.CLI, "codex", "gpt-frozen", null, "high");
        String brainId = "brain-1";
        when(threadStore.findThreadById(brainId))
                .thenReturn(Optional.of(brainThread(brainId, frozen)));

        WorkModelResolver.Resolved got = resolver.resolveForThread(brainId);

        assertThat(got.choice()).isEqualTo(frozen);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.THREAD);
        assertThat(got.provenance().scopeLabel()).isEqualTo("parent trunk snapshot · plan");
        verify(engineSettings, never()).forAudience(any(), any());
    }

    @Test
    void aCiFixingStageResolvesUnderTheCiFixRow()
    {
        when(stageStore.findStageById(STAGE_ID))
                .thenReturn(Optional.of(stage(StageType.CI_FIXING_STAGE, null)));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(null)));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        when(engineSettings.forAudience(WS_ID, "ci-fix"))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(CODEX, true)));

        assertThat(resolver.resolveForStage(THREAD_ID, TASK_ID, STAGE_ID.toString()).choice())
                .isEqualTo(CODEX);
    }

    @Test
    void aTrunksOwnPinBeatsTheWorkspaceRowForThatAudienceOnly()
    {
        WorkModel pinned = new WorkModel(WorkModelKind.API, "deepseek", null, "work");
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(effort("high"))));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        when(engineSettings.forAudience(eq(WS_ID), any()))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(CODEX, true)));
        when(threadEngines.forAudience(THREAD_ID, "review")).thenReturn(Optional.of(pinned));

        // Only the review row was pinned, so a dev turn still runs the
        // workspace's agent...
        assertThat(resolver.resolveForTask(THREAD_ID, TASK_ID).choice().agentOrProvider())
                .isEqualTo("codex");

        // ...while a review-round stage under the same trunk takes the pin,
        // still wearing the nearest scope's effort.
        when(stageStore.findStageById(STAGE_ID))
                .thenReturn(Optional.of(stage(StageType.REVIEW_ROUND_STAGE, null)));
        WorkModelResolver.Resolved got =
                resolver.resolveForStage(THREAD_ID, TASK_ID, STAGE_ID.toString());

        assertThat(got.choice().agentOrProvider()).isEqualTo("deepseek");
        assertThat(got.choice().reasoningEffort()).isEqualTo("high");
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.THREAD);
        assertThat(got.provenance().scopeLabel()).isEqualTo("this trunk · review");
    }

    @Test
    void aRoleRowInheritingTheWorkspaceDefaultIsNotLabelledWithTheRole()
    {
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        when(engineSettings.forAudience(eq(WS_ID), any()))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(CODEX, false)));

        assertThat(resolver.resolveForThread(THREAD_ID).provenance().scopeLabel())
                .isEqualTo("workspace ByteQuay");
    }

    @Test
    void theWorkspaceOverrideColumnStillAppliesWhenSettingsCarryNoEngine()
    {
        WorkModel column = new WorkModel(WorkModelKind.API, "openai", "gpt-5", "team");
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(column)));
        when(engineSettings.forAudience(eq(WS_ID), any())).thenReturn(Optional.empty());

        WorkModelResolver.Resolved got = resolver.resolveForThread(THREAD_ID);

        assertThat(got.choice()).isEqualTo(column);
        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.WORKSPACE);
    }

    @Test
    void theCuratedDefaultAppliesWhenTheWorkspaceConfiguredNothing()
    {
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        when(engineSettings.forAudience(eq(WS_ID), any())).thenReturn(Optional.empty());

        WorkModelResolver.Resolved got = resolver.resolveForThread(THREAD_ID);

        assertThat(got.provenance().source()).isEqualTo(WorkModelResolver.Source.GLOBAL_DEFAULT);
        // Asserted via the catalog so reordering it flips the test rather
        // than burying a hard-coded "claude-code" here.
        WorkModelCatalog.CatalogAgent expected = WorkModelCatalog.CLI_AGENTS.get(0);
        assertThat(got.choice().kind()).isEqualTo(WorkModelKind.CLI);
        assertThat(got.choice().agentOrProvider()).isEqualTo(expected.id());
        assertThat(got.choice().model()).isEqualTo(expected.defaultModel().id());
        assertThat(got.choice().account()).isNull();
        assertThat(got.provenance().scopeId()).isNull();
    }

    @Test
    void aScopeOverrideChangesReasoningEffortAndNothingElse()
    {
        // An engine stored on the stage row by an older build must not move
        // the session off the workspace's agent — only its effort survives.
        WorkModel staleStagePick = new WorkModel(
                WorkModelKind.API, "anthropic", "claude-opus-4-8", null, "xhigh");
        when(stageStore.findStageById(STAGE_ID))
                .thenReturn(Optional.of(stage(StageType.DEVELOPMENT_STAGE, staleStagePick)));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(null)));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        when(engineSettings.forAudience(eq(WS_ID), any()))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(CODEX, true)));

        WorkModel got = resolver.resolveForStage(THREAD_ID, TASK_ID, STAGE_ID.toString()).choice();

        assertThat(got.kind()).isEqualTo(WorkModelKind.CLI);
        assertThat(got.agentOrProvider()).isEqualTo("codex");
        assertThat(got.model()).isNull();
        assertThat(got.reasoningEffort()).isEqualTo("xhigh");
    }

    @Test
    void theNearestScopesEffortWins()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(effort("high"))));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(effort("low"))));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        when(engineSettings.forAudience(eq(WS_ID), any()))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(CODEX, true)));

        assertThat(resolver.resolveForTask(THREAD_ID, TASK_ID).choice().reasoningEffort())
                .isEqualTo("high");
        assertThat(resolver.resolveForThread(THREAD_ID).choice().reasoningEffort())
                .isEqualTo("low");
    }

    @Test
    void theWorkspacesOwnEffortAppliesWhenNoScopeOverrides()
    {
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread(null)));
        when(workspaceStore.findWorkspaceById(WS_ID)).thenReturn(Optional.of(workspace(null)));
        when(engineSettings.forAudience(eq(WS_ID), any()))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(
                        new WorkModel(WorkModelKind.CLI, "codex", null, null, "medium"), true)));

        assertThat(resolver.resolveForThread(THREAD_ID).choice().reasoningEffort())
                .isEqualTo("medium");
    }

    @Test
    void resolveForTaskRejectsTaskThatBelongsToAnotherThread()
    {
        Task template = task(null);
        Task crossThread = new Task(
                template.id(), "different-thread", template.seq(), template.status(),
                template.branchName(), template.worktreePath(),
                template.baseBranch(), template.workingDir(),
                template.processPid(), template.logPath(),
                template.prNumber(), template.prState(), template.ciState(),
                template.taskType(), template.linkedPrNumber(), template.linkedIssueNumber(),
                template.costUsdMilli(), template.tokensIn(), template.tokensOut(),
                template.agentSessionId(),
                template.createdAt(), template.endedAt(), template.errorMessage(),
                template.name(), template.roleSkill(), template.workModel());
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

    private static WorkModel effort(String reasoningEffort)
    {
        return new WorkModel(WorkModelKind.CLI, "claude-code", null, null, reasoningEffort);
    }

    private static StageInstance stage(StageType type, WorkModel workModel)
    {
        return new StageInstance(
                STAGE_ID, TASK_ID, type, StageState.OPEN, NOW, null, null, workModel);
    }

    private static Thread thread(WorkModel workModel)
    {
        return new Thread(
                THREAD_ID, ThreadKind.CLI_AGENT, "claude-code", null,
                "Resolver fixture", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, WS_ID, workModel, null);
    }

    private static Thread brainThread(String id, WorkModel workModel)
    {
        return new Thread(
                id, ThreadKind.BRAIN_AGENT, workModel.agentOrProvider(), null,
                "Brain fixture", ThreadStatus.IDLE, workModel.model(),
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, WS_ID, workModel,
                null, 1, TASK_ID);
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
