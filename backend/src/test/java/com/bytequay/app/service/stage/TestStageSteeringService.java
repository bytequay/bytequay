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
package com.bytequay.app.service.stage;

import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestStageSteeringService
{
    private static final Instant NOW = Instant.parse("2026-06-21T09:00:00Z");

    private StageStore stageStore;
    private TaskStore taskStore;
    private ThreadStore threadStore;
    private ThreadTurnScheduler scheduler;
    private AgentRunService agentRuns;
    private TaskCommandExecutor commands;
    private IterationService iterationService;
    private ChatAttachmentStore attachmentStore;
    private StageSteeringServiceImpl service;
    private boolean commandActive;

    @BeforeEach
    void setUp()
    {
        stageStore = mock(StageStore.class);
        taskStore = mock(TaskStore.class);
        threadStore = mock(ThreadStore.class);
        scheduler = mock(ThreadTurnScheduler.class);
        agentRuns = mock(AgentRunService.class);
        commands = mock(TaskCommandExecutor.class);
        iterationService = mock(IterationService.class);
        attachmentStore = mock(ChatAttachmentStore.class);
        when(attachmentStore.save(any(), any())).thenReturn(List.of());
        when(commands.execute(anyString(), any())).thenAnswer(invocation -> {
            commandActive = true;
            try {
                return ((Supplier<?>) invocation.getArgument(1)).get();
            }
            finally {
                commandActive = false;
            }
        });
        service = new StageSteeringServiceImpl(
                stageStore, taskStore, threadStore, scheduler, agentRuns, commands,
                iterationService, attachmentStore, new ObjectMapper());
    }

    @Test
    void steeringOpensItsSessionTurnAndIterationInsideOneTaskCommand()
    {
        UUID stageId = UUID.randomUUID();
        AgentRun run = mock(AgentRun.class);
        when(run.id()).thenReturn("run-3");
        when(stageStore.findStageById(stageId)).thenReturn(Optional.of(
                stage(stageId, "task-7", StageState.OPEN)));
        when(taskStore.findTaskById("task-7")).thenReturn(Optional.of(task("task-7", "thread-9")));
        when(threadStore.findThreadById("thread-9")).thenReturn(Optional.of(thread("thread-9")));
        when(agentRuns.openSchedulerSessionInCommand(
                any(), eq("task-7"), eq(stageId.toString()), eq(AgentRun.KIND_CI_FIX),
                eq("Fix the retry default")))
                .thenAnswer(invocation -> {
                    assertThat(commandActive).isTrue();
                    return run;
                });
        when(scheduler.enqueueStageTurn(
                any(), any(), eq("task-7"), eq(stageId.toString()), any(), eq("run-3")))
                .thenAnswer(invocation -> {
                    assertThat(commandActive).isTrue();
                    return "turn-3";
                });
        doAnswer(invocation -> {
            assertThat(commandActive).isTrue();
            return null;
        }).when(iterationService).begin(
                "task-7", "turn-3", IterationService.TRIGGER_USER_STEERING);

        StageSteeringService.SteerResult result = service.steer(
                stageId, "Fix the retry default", null);

        assertThat(result.turnId()).isEqualTo("turn-3");
        assertThat(commandActive).isFalse();
        verify(commands).execute(eq("task-7"), any());
        verify(agentRuns).openSchedulerSessionInCommand(
                any(), eq("task-7"), eq(stageId.toString()), eq(AgentRun.KIND_CI_FIX),
                eq("Fix the retry default"));
        verify(scheduler).enqueueStageTurn(
                any(), eq("Fix the retry default"), eq("task-7"), eq(stageId.toString()),
                any(TurnInitiator.class), eq("run-3"));
        verify(scheduler, never()).enqueueStageTurn(
                any(), any(), any(), any(), any());
        verify(iterationService).begin(
                "task-7", "turn-3", IterationService.TRIGGER_USER_STEERING);
    }

    @Test
    void v2StageRoutesOnlyToTypedControl()
    {
        UUID stageId = UUID.randomUUID();
        V2ControlRouteStore routes = mock(V2ControlRouteStore.class);
        V2StageSteeringControl typed = mock(V2StageSteeringControl.class);
        when(routes.taskForStage(stageId.toString()))
                .thenReturn(Optional.of("task-v2"));
        when(typed.steer(
                "task-v2", stageId.toString(), "change course", List.of(),
                V2StageSteeringControl.Mode.APPEND)).thenReturn("stage-turn-v2");
        service.setV2Routes(routes);
        service.setV2Steering(typed);

        StageSteeringService.SteerResult result = service.steer(
                stageId, " change course ", List.of());

        assertThat(result.turnId()).isEqualTo("stage-turn-v2");
        verify(typed).steer(
                "task-v2", stageId.toString(), "change course", List.of(),
                V2StageSteeringControl.Mode.APPEND);
        verify(stageStore, never()).findStageById(any());
        verify(agentRuns, never()).openSchedulerSessionInCommand(
                any(), any(), any(), any(), any());
        verify(scheduler, never()).enqueueStageTurn(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void enqueuesATaskBoundSteeringTurnAndOpensAnIteration()
    {
        UUID stageId = UUID.randomUUID();
        when(stageStore.findStageById(stageId)).thenReturn(Optional.of(
                stage(stageId, "task-7", StageState.OPEN)));
        when(taskStore.findTaskById("task-7")).thenReturn(Optional.of(task("task-7", "thread-9")));
        when(threadStore.findThreadById("thread-9")).thenReturn(Optional.of(thread("thread-9")));
        // Bound to the explicit task id (not the active-task projection) so a
        // task parked at AWAITING_REVIEW still routes to the dev agent.
        schedulerSession("run-3");
        when(scheduler.enqueueStageTurn(
                any(), eq("Fix the retry default"), eq("task-7"), anyString(), any(), eq("run-3")))
                .thenReturn("turn-3");

        StageSteeringService.SteerResult result = service.steer(stageId, "  Fix the retry default  ", null);

        assertThat(result.turnId()).isEqualTo("turn-3");
        verify(scheduler).enqueueStageTurn(
                any(), eq("Fix the retry default"), eq("task-7"), eq(stageId.toString()),
                any(TurnInitiator.class), eq("run-3"));
        verify(scheduler, never()).enqueueTrunkTurn(
                any(), any(), any(TurnInitiator.class));
        verify(iterationService).begin("task-7", "turn-3", IterationService.TRIGGER_USER_STEERING);
    }

    @Test
    void pendingTaskCanQueueSteeringForItsExactOpenStage()
    {
        UUID stageId = UUID.randomUUID();
        when(stageStore.findStageById(stageId)).thenReturn(Optional.of(
                stage(stageId, "task-7", StageState.OPEN)));
        when(taskStore.findTaskById("task-7")).thenReturn(Optional.of(
                task("task-7", "thread-9").withStatus(TaskStatus.PENDING)));
        when(threadStore.findThreadById("thread-9")).thenReturn(Optional.of(thread("thread-9")));
        schedulerSession("queued-run");
        when(scheduler.enqueueStageTurn(
                any(), any(), eq("task-7"), eq(stageId.toString()), any(), eq("queued-run")))
                .thenReturn("queued-steer");

        StageSteeringService.SteerResult result = service.steer(
                stageId, "Apply this when the current work starts", null);

        assertThat(result.turnId()).isEqualTo("queued-steer");
        verify(scheduler).enqueueStageTurn(
                any(), eq("Apply this when the current work starts"), eq("task-7"),
                eq(stageId.toString()), any(TurnInitiator.class), eq("queued-run"));
        verify(iterationService).begin(
                "task-7", "queued-steer", IterationService.TRIGGER_USER_STEERING);
    }

    @Test
    void foldsPastedImagesIntoTheTurnInput()
    {
        UUID stageId = UUID.randomUUID();
        when(stageStore.findStageById(stageId)).thenReturn(Optional.of(
                stage(stageId, "task-7", StageState.OPEN)));
        when(taskStore.findTaskById("task-7")).thenReturn(Optional.of(task("task-7", "thread-9")));
        when(threadStore.findThreadById("thread-9")).thenReturn(Optional.of(thread("thread-9")));
        when(attachmentStore.save(eq("thread-9"), eq(List.of("data:image/png;base64,abc"))))
                .thenReturn(List.of("/tmp/attachments/thread-9/img.png"));
        schedulerSession("run-4");
        when(scheduler.enqueueStageTurn(
                any(), any(), eq("task-7"), anyString(), any(), eq("run-4")))
                .thenReturn("turn-4");

        service.steer(stageId, "see this", List.of("data:image/png;base64,abc"));

        verify(scheduler).enqueueStageTurn(
                any(), argThat(input -> input.contains("/tmp/attachments/thread-9/img.png")),
                eq("task-7"), eq(stageId.toString()), any(TurnInitiator.class), eq("run-4"));
    }

    @Test
    void parkedQuestionIsReadOnlyAndDoesNotConsumeAnIteration()
    {
        UUID stageId = UUID.randomUUID();
        Task parked = new Task(
                "task-7", "thread-9", 1L, TaskStatus.NEEDS_ATTENTION, "feature", null,
                "main", "/tmp", null, null, 42, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null, null,
                TaskPhase.NEEDS_ATTENTION, null, 0, null);
        when(stageStore.findStageById(stageId)).thenReturn(Optional.of(
                stage(stageId, "task-7", StageState.OPEN)));
        when(taskStore.findTaskById("task-7")).thenReturn(Optional.of(parked));
        when(threadStore.findThreadById("thread-9")).thenReturn(Optional.of(thread("thread-9")));
        schedulerSession("question-run");
        when(scheduler.enqueueStageTurn(any(), any(), any(), anyString(), any(), eq("question-run")))
                .thenReturn("question-turn");

        service.steer(stageId, "Why did CI pass later?", null);

        verify(scheduler).enqueueStageTurn(
                any(), eq("Why did CI pass later?"), eq("task-7"), eq(stageId.toString()),
                argThat(initiator -> initiator.attended()
                        && TurnInitiator.SOURCE_PARKED_STEERING.equals(initiator.source())),
                eq("question-run"));
        verify(iterationService, never()).begin(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsAClosedStage()
    {
        UUID stageId = UUID.randomUUID();
        when(stageStore.findStageById(stageId)).thenReturn(Optional.of(
                stage(stageId, "task-7", StageState.CLOSED)));

        assertThatThrownBy(() -> service.steer(stageId, "go", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(422));
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any());
    }

    @Test
    void rechecksTheStageAfterTakingTheTaskCommand()
    {
        UUID stageId = UUID.randomUUID();
        when(stageStore.findStageById(stageId)).thenReturn(
                Optional.of(stage(stageId, "task-7", StageState.OPEN)),
                Optional.of(stage(stageId, "task-7", StageState.CLOSED)));

        assertThatThrownBy(() -> service.steer(stageId, "go", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(422));
        verify(commands).execute(eq("task-7"), any());
        verify(agentRuns, never()).openSchedulerSessionInCommand(
                any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAnEmptyMessage()
    {
        UUID stageId = UUID.randomUUID();

        assertThatThrownBy(() -> service.steer(stageId, "   ", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(400));
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any());
    }

    private static StageInstance stage(UUID id, String taskId, StageState state)
    {
        return new StageInstance(id, taskId, StageType.CI_FIXING_STAGE, state, NOW, null, null);
    }

    private void schedulerSession(String id)
    {
        AgentRun run = mock(AgentRun.class);
        when(run.id()).thenReturn(id);
        when(agentRuns.openSchedulerSessionInCommand(any(), any(), any(), any(), any()))
                .thenReturn(run);
    }

    private static Task task(String id, String threadId)
    {
        return new Task(
                id, threadId, 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, 42, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null, null,
                TaskPhase.PUSHED_AWAITING_CI, null, 0, null);
    }

    private static Thread thread(String id)
    {
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", null, "Dev thread",
                ThreadStatus.RUNNING, "claude-sonnet-4.6", 0L, 0L, 0L,
                NOW, NOW, null, null, ThreadFlow.BUILD, "ws-default", null, null);
    }
}
