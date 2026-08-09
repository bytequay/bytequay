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
package com.bytequay.app.service.threads;

import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.compatibility.V2TrunkRuntimeProjection;
import com.bytequay.app.developmentflow.task.creation.V2TaskCreationService;
import com.bytequay.app.developmentflow.trunk.V2ThreadControlService;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadGroup;
import com.bytequay.app.domain.ThreadGroupMembership;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnEventType;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.IdSequenceStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadGroupStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.workspaces.WorkspaceDataPurger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestThreadServiceScheduler
{
    @Test
    void legacyTrunkMetadataPatchFailsClosed()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        Thread legacy = thread("trunk-legacy");
        Mockito.when(store.findThreadById(legacy.id()))
                .thenReturn(Optional.of(legacy));
        Mockito.when(store.findTurnVersion(legacy.id()))
                .thenReturn(Optional.of("LEGACY"));
        ThreadService service = service(
                store, Mockito.mock(ThreadGroupStore.class));

        assertThatThrownBy(() -> service.patchTask(
                legacy.id(), new ThreadService.TaskPatch("renamed")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        Mockito.verify(store, Mockito.never()).saveThread(Mockito.any());
    }

    @Test
    void threadReadsAndStatusListsUseTheV2RuntimeProjection()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        Thread stored = thread("v2-stale");
        stored = new Thread(
                stored.id(), stored.kind(), stored.provider(), "legacy-session",
                stored.title(), ThreadStatus.ERRORED, stored.model(),
                stored.costUsdMilli(), stored.tokensIn(), stored.tokensOut(),
                stored.createdAt(), stored.updatedAt(), stored.updatedAt(),
                "legacy error", stored.flow(), stored.workspaceId(),
                stored.workModel(), stored.parentReviewPassId(),
                stored.parallelSlots(), stored.parentTaskId(), stored.prRef(),
                stored.description());
        Thread idle = new Thread(
                stored.id(), stored.kind(), stored.provider(), null,
                stored.title(), ThreadStatus.IDLE, stored.model(),
                stored.costUsdMilli(), stored.tokensIn(), stored.tokensOut(),
                stored.createdAt(), stored.updatedAt(), null, null,
                stored.flow(), stored.workspaceId(), stored.workModel(),
                stored.parentReviewPassId(), stored.parallelSlots(),
                stored.parentTaskId(), stored.prRef(), stored.description());
        V2TrunkRuntimeProjection runtime =
                Mockito.mock(V2TrunkRuntimeProjection.class);
        Mockito.when(runtime.project(stored)).thenReturn(idle);
        Mockito.when(runtime.projectAll(List.of(stored)))
                .thenReturn(List.of(idle));
        Mockito.when(runtime.count(null)).thenReturn(1);
        Mockito.when(runtime.listIds(ThreadStatus.IDLE, null, 10))
                .thenReturn(List.of(stored.id()));
        Mockito.when(runtime.listIds(ThreadStatus.ERRORED, null, 10))
                .thenReturn(List.of());
        Mockito.when(store.findThreadById(stored.id()))
                .thenReturn(Optional.of(stored));
        Mockito.when(store.listTasksByStatus(ThreadStatus.ERRORED, 11))
                .thenReturn(List.of(stored));
        Mockito.when(store.listTasksByStatus(ThreadStatus.IDLE, 11))
                .thenReturn(List.of());
        Mockito.when(store.listTasksByIds(List.of(stored.id())))
                .thenReturn(List.of(stored));
        ThreadService service = new ThreadService(
                store, Mockito.mock(TaskStore.class),
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class), new GitRunner(),
                noopWorktreeService(), stubIdGenerator(),
                Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2TrunkRuntime(runtime);

        assertThat(service.find(stored.id())).contains(idle);
        assertThat(service.listByStatus(ThreadStatus.IDLE, 10))
                .containsExactly(idle);
        assertThat(service.listByStatus(ThreadStatus.ERRORED, 10)).isEmpty();
    }

    @Test
    void statusListsDropV2RowsThatChangeStatusAfterIdSelection()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        Thread stored = thread("v2-racing", ThreadStatus.ERRORED);
        Thread running = copyThread(
                stored, ThreadStatus.RUNNING, stored.updatedAt());
        V2TrunkRuntimeProjection runtime =
                Mockito.mock(V2TrunkRuntimeProjection.class);
        Mockito.when(runtime.count(null)).thenReturn(1);
        Mockito.when(runtime.count(stored.workspaceId())).thenReturn(1);
        Mockito.when(runtime.listIds(ThreadStatus.IDLE, null, 10))
                .thenReturn(List.of(stored.id()));
        Mockito.when(runtime.listIds(
                        ThreadStatus.IDLE, stored.workspaceId(), 10))
                .thenReturn(List.of(stored.id()));
        Mockito.when(store.listTasksByStatus(ThreadStatus.IDLE, 11))
                .thenReturn(List.of());
        Mockito.when(store.listTasksByWorkspaceAndStatus(
                        stored.workspaceId(), ThreadStatus.IDLE, 11))
                .thenReturn(List.of());
        Mockito.when(store.listTasksByIds(List.of(stored.id())))
                .thenReturn(List.of(stored));
        Mockito.when(runtime.projectAll(List.of(stored)))
                .thenReturn(List.of(running));
        ThreadService service = service(store, Mockito.mock(ThreadGroupStore.class));
        service.setV2TrunkRuntime(runtime);

        assertThat(service.listByStatus(ThreadStatus.IDLE, 10)).isEmpty();
        assertThat(service.listByWorkspaceAndStatus(
                stored.workspaceId(), ThreadStatus.IDLE, 10)).isEmpty();

        Mockito.verify(runtime, Mockito.times(2)).projectAll(List.of(stored));
        Mockito.verify(runtime, Mockito.never()).find(Mockito.anyString());
        Mockito.verify(runtime, Mockito.never()).project(Mockito.any());
    }

    @Test
    void groupLimitUsesProjectedActivityOrder()
    {
        Instant base = Instant.parse("2026-05-18T12:00:00Z");
        Thread first = copyThread(thread("first"), ThreadStatus.IDLE,
                base.plusSeconds(3));
        Thread second = copyThread(thread("second"), ThreadStatus.IDLE,
                base.plusSeconds(2));
        Thread third = copyThread(thread("third"), ThreadStatus.IDLE,
                base.plusSeconds(1));
        Thread projectedThird = copyThread(
                third, ThreadStatus.IDLE, base.plusSeconds(4));
        ThreadStore store = Mockito.mock(ThreadStore.class);
        ThreadGroupStore groups = Mockito.mock(ThreadGroupStore.class);
        V2TrunkRuntimeProjection runtime =
                Mockito.mock(V2TrunkRuntimeProjection.class);
        List<Thread> stored = List.of(first, second, third);
        Mockito.when(groups.listMembers("group-1")).thenReturn(List.of(
                new ThreadGroupMembership("first", "group-1", base),
                new ThreadGroupMembership("second", "group-1", base),
                new ThreadGroupMembership("third", "group-1", base)));
        Mockito.when(store.listTasksByIds(List.of("first", "second", "third")))
                .thenReturn(stored);
        Mockito.when(runtime.projectAll(stored))
                .thenReturn(List.of(first, second, projectedThird));
        ThreadService service = service(store, groups);
        service.setV2TrunkRuntime(runtime);

        assertThat(service.listByGroup("group-1", 2))
                .containsExactly(projectedThird, first);
    }

    @Test
    void v2TrunkReadsRetainLegacyHistoryAfterPromotion()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        ThreadTurnStore turns = Mockito.mock(ThreadTurnStore.class);
        ThreadTurnEventStore events = Mockito.mock(ThreadTurnEventStore.class);
        V2ThreadControlService typed = Mockito.mock(V2ThreadControlService.class);
        Thread trunk = thread("promoted-trunk");
        Instant old = Instant.parse("2026-05-18T12:00:00Z");
        Instant fresh = old.plusSeconds(1);
        ThreadMessage legacyMessage = Mockito.mock(ThreadMessage.class);
        ThreadMessage typedMessage = Mockito.mock(ThreadMessage.class);
        ThreadTurn legacyTurn = Mockito.mock(ThreadTurn.class);
        ThreadTurn typedTurn = Mockito.mock(ThreadTurn.class);
        ThreadTurnEvent legacyEvent = Mockito.mock(ThreadTurnEvent.class);
        ThreadTurnEvent typedEvent = Mockito.mock(ThreadTurnEvent.class);
        Mockito.when(legacyMessage.id()).thenReturn("legacy-message");
        Mockito.when(legacyMessage.ts()).thenReturn(old);
        Mockito.when(typedMessage.id()).thenReturn("typed-message");
        Mockito.when(typedMessage.ts()).thenReturn(fresh);
        Mockito.when(legacyTurn.id()).thenReturn("legacy-turn");
        Mockito.when(legacyTurn.createdAt()).thenReturn(old);
        Mockito.when(typedTurn.id()).thenReturn("typed-turn");
        Mockito.when(typedTurn.createdAt()).thenReturn(fresh);
        Mockito.when(legacyEvent.id()).thenReturn("legacy-event");
        Mockito.when(legacyEvent.createdAt()).thenReturn(old);
        Mockito.when(typedEvent.id()).thenReturn("typed-event");
        Mockito.when(typedEvent.createdAt()).thenReturn(fresh);
        Mockito.when(store.findThreadById(trunk.id())).thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion(trunk.id())).thenReturn(Optional.of("V2"));
        Mockito.when(store.listMessages(trunk.id())).thenReturn(List.of(legacyMessage));
        Mockito.when(typed.history(trunk.id())).thenReturn(List.of(typedMessage));
        Mockito.when(turns.listTurnsByTaskId(trunk.id(), 50))
                .thenReturn(List.of(legacyTurn));
        Mockito.when(typed.turns(trunk.id(), 50)).thenReturn(List.of(typedTurn));
        Mockito.when(events.listEventsByTaskId(trunk.id(), 200))
                .thenReturn(List.of(legacyEvent));
        Mockito.when(typed.turnEvents(trunk.id())).thenReturn(List.of(typedEvent));
        ThreadService service = new ThreadService(
                store,
                Mockito.mock(TaskStore.class),
                Mockito.mock(ThreadGroupStore.class),
                turns,
                events,
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2ThreadControls(typed);

        assertThat(service.history(trunk.id()))
                .containsExactly(legacyMessage, typedMessage);
        assertThat(service.turns(trunk.id()))
                .containsExactly(typedTurn, legacyTurn);
        assertThat(service.turnEvents(trunk.id()))
                .containsExactly(typedEvent, legacyEvent);
    }

    @Test
    void routedLegacyTrunkSendFailsClosedWithoutPromotion()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        V2TaskCreationService taskCreation = Mockito.mock(V2TaskCreationService.class);
        V2ThreadControlService typed = Mockito.mock(V2ThreadControlService.class);
        Thread trunk = thread("trunk-legacy");
        Mockito.when(store.findThreadById(trunk.id())).thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion(trunk.id())).thenReturn(Optional.of("LEGACY"));
        ThreadService service = new ThreadService(
                store,
                Mockito.mock(TaskStore.class),
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2TaskCreation(taskCreation);
        service.setV2ThreadControls(typed);

        assertThatThrownBy(() -> service.sendTrunk(trunk.id(), "plan this"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void routedLegacyTrunkIsNotPromotedWithoutItsTypedRuntime()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        V2TaskCreationService taskCreation = Mockito.mock(V2TaskCreationService.class);
        Thread trunk = thread("trunk-legacy");
        Mockito.when(store.findThreadById(trunk.id())).thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion(trunk.id())).thenReturn(Optional.of("LEGACY"));
        Mockito.when(taskCreation.routes(trunk.workspaceId())).thenReturn(true);
        ThreadService service = new ThreadService(
                store,
                Mockito.mock(TaskStore.class),
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2TaskCreation(taskCreation);

        assertThatThrownBy(() -> service.sendTrunk(trunk.id(), "plan this"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        Mockito.verify(taskCreation, Mockito.never())
                .prepareTrunk(trunk.id(), trunk.workspaceId());
    }

    @Test
    void routedLegacyTrunkCannotMaterialiseANewTask()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        TaskStore tasks = Mockito.mock(TaskStore.class);
        WorktreeService worktrees = Mockito.mock(WorktreeService.class);
        V2TaskCreationService taskCreation = Mockito.mock(V2TaskCreationService.class);
        Thread trunk = thread("trunk-legacy");
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6",
                "Fix tests", "/tmp/repo", "main", "please fix", List.of(),
                "DEVELOP", null, null, null, trunk.workspaceId(), null);
        Mockito.when(store.findThreadById(trunk.id())).thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion(trunk.id())).thenReturn(Optional.of("LEGACY"));
        ThreadService service = new ThreadService(
                store,
                tasks,
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                worktrees,
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2TaskCreation(taskCreation);

        assertThatThrownBy(() -> service.materialiseTask(trunk.id(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        Mockito.verifyNoInteractions(taskCreation);
    }

    @Test
    void v2TrunkSendUsesTypedRouteWithoutSchedulerOrRegistry()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        V2ThreadControlService typed = Mockito.mock(V2ThreadControlService.class);
        Thread trunk = thread("trunk-v2");
        Mockito.when(store.findThreadById("trunk-v2"))
                .thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion("trunk-v2"))
                .thenReturn(Optional.of("V2"));
        Mockito.when(typed.send(trunk, "plan this", TurnInitiator.user()))
                .thenReturn("thread-turn-v2");
        ThreadService service = new ThreadService(
                store,
                Mockito.mock(TaskStore.class),
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2ThreadControls(typed);

        assertThat(service.sendTrunk("trunk-v2", "plan this"))
                .isEqualTo("thread-turn-v2");
        Mockito.verify(typed).send(trunk, "plan this", TurnInitiator.user());
    }

    @Test
    void legacyPermissionControlsRequireTheTypedExecutionEndpoint()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        Thread trunk = thread("trunk-legacy");
        Mockito.when(store.findThreadById(trunk.id()))
                .thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion(trunk.id()))
                .thenReturn(Optional.of("V2"));
        ThreadService service = new ThreadService(
                store,
                Mockito.mock(TaskStore.class),
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.decide(
                trunk.id(), "call-1", PermissionDecision.ALLOW, "Bash", 5))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("typed V2 execution endpoint");
        assertThatThrownBy(() -> service.tryConsumeToolBudget(
                trunk.id(), "stage-1", "Bash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("typed V2 execution endpoint");
    }

    @Test
    void promotedTrunkRoutesPermissionsOnlyToAnExactLegacyTaskAgent()
    {
        String trunkId = "promoted-trunk";
        ThreadStore store = Mockito.mock(ThreadStore.class);
        TaskStore tasks = Mockito.mock(TaskStore.class);
        Mockito.when(store.findTurnVersion(trunkId)).thenReturn(Optional.of("V2"));
        Mockito.when(store.findThreadById(trunkId))
                .thenReturn(Optional.of(thread(trunkId)));
        ThreadService service = new ThreadService(
                store,
                tasks,
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.notifyPermissionRequested(
                trunkId, "legacy-task", "call-1", "Bash", "run tests"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("typed V2 execution endpoint");
        for (String rejected : List.of("v2-task", "sibling-task", "unknown", "trunk")) {
            assertThatThrownBy(() -> service.notifyPermissionRequested(
                    trunkId, rejected, "call-2", "Bash", "run tests"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("typed V2 execution endpoint");
        }
    }

    @Test
    void createDoesNotEnqueueTrunkTurnFromInitialPrompt()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));
        enableV2Creation(service);
        // initialPrompt feeds title derivation but is treated as
        // context the create dialog will stage in the trunk composer,
        // not as a turn to fire at the agent.
        Thread created = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                /* title */ null,
                "/tmp/work",
                "main",
                "please fix the broken tests",
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null)
                .withDescription("Focused remark for this trunk"));

        assertThat(store.threads).hasSize(1);
        assertThat(created.title()).isEqualTo("Please fix the broken tests");
        assertThat(created.description()).isEqualTo("Focused remark for this trunk");
        // A NewTaskRequest with no flow defaults to BUILD per the
        // V74 column default and the design's "BUILD threads are the
        // overwhelming majority" guidance.
        assertThat(store.threads.values().iterator().next().flow())
                .isEqualTo(ThreadFlow.BUILD);
    }

    @Test
    void createDisambiguatesTitleCollisionsWithinAWorkspaceOnly()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));
        enableV2Creation(service);

        NewThreadRequestBuilder request = title -> new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", title,
                "/tmp/work", "main", /* initialPrompt */ null, List.of(), "DEVELOP",
                null, null, null, "ws-default", null);

        Thread first = service.create(request.of("Fix the broken tests"));
        Thread second = service.create(request.of("Fix the broken tests"));
        Thread third = service.create(request.of("Fix the broken tests"));
        assertThat(first.title()).isEqualTo("Fix the broken tests");
        assertThat(second.title()).isEqualTo("Fix the broken tests (2)");
        assertThat(third.title()).isEqualTo("Fix the broken tests (3)");

        // Same title, different workspace — no collision, no suffix.
        Thread otherWorkspace = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", "Fix the broken tests",
                "/tmp/work", "main", null, List.of(), "DEVELOP",
                null, null, null, "ws-other", null));
        assertThat(otherWorkspace.title()).isEqualTo("Fix the broken tests");
    }

    @FunctionalInterface
    private interface NewThreadRequestBuilder
    {
        ThreadService.NewTaskRequest of(String title);
    }

    @Test
    void createHonoursReviewFlowOnTheRequest()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));
        enableV2Creation(service);

        service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Review PR #42",
                "/tmp/work",
                "main",
                /* initialPrompt */ null,
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ 42,
                /* linkedIssueNumber */ null,
                ThreadFlow.REVIEW, "ws-default", /* workModel */ null));

        assertThat(store.threads).hasSize(1);
        assertThat(store.threads.values().iterator().next().flow())
                .isEqualTo(ThreadFlow.REVIEW);
    }

    @Test
    void createWithoutPromptDoesNotStartSession()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));
        enableV2Creation(service);

        service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/work",
                "main",
                " ",
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));
    }

    @Test
    void legacyTaskFollowUpSendFailsClosed()
    {
        Thread thread = thread();
        Task task = new Task(
                "task-1", thread.id(), 1L, TaskStatus.IDLE,
                "dev/thread-1", "/tmp/work/.wt/task-1", "main", "/tmp/work",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        ThreadService service = new ThreadService(
                store,
                new LatestOnlyTaskStore(task),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.send(thread.id(), task.id(), "next"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void followUpSendRejectsAnUnaddressedTask()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        Task parked = new Task(
                "task-9", thread.id(), 1L, TaskStatus.AWAITING_REVIEW,
                "dev/thread-1", "/tmp/work/.wt/task-9", "main", "/tmp/work",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        ThreadService service = new ThreadService(
                store,
                new LatestOnlyTaskStore(parked),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.send(thread.id(), null, "keep going"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId is required");
    }

    @Test
    void turnsReturnDurableHistoryForTaskOnly()
    {
        Thread thread = thread();
        Thread otherTask = thread("thread-2");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        store.saveThread(otherTask);
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("turn-1", thread.id(), now.minusSeconds(10)));
        turns.saveTurn(turn("turn-2", otherTask.id(), now));
        turns.saveTurn(turn("turn-3", thread.id(), now.plusSeconds(10)));
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.turns(thread.id()))
                .extracting(ThreadTurn::id)
                .containsExactly("turn-3", "turn-1");
    }

    @Test
    void turnsUseStableTieBreakerForMatchingTimestamps()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("turn-a", thread.id(), now));
        turns.saveTurn(turn("turn-c", thread.id(), now));
        turns.saveTurn(turn("turn-b", thread.id(), now.minusSeconds(1)));
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.turns(thread.id()))
                .extracting(ThreadTurn::id)
                .containsExactly("turn-c", "turn-a", "turn-b");
    }

    @Test
    void turnEventsReturnDurableHistoryForTaskOnly()
    {
        Thread thread = thread();
        Thread otherTask = thread("thread-2");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        store.saveThread(otherTask);
        InMemoryTaskTurnEventStore turnEvents = new InMemoryTaskTurnEventStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turnEvents.appendEvent(turnEvent("event-1", "turn-1", thread.id(), now.minusSeconds(10)));
        turnEvents.appendEvent(turnEvent("event-2", "turn-2", otherTask.id(), now));
        turnEvents.appendEvent(turnEvent("event-3", "turn-3", thread.id(), now.plusSeconds(10)));
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                turnEvents,
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.turnEvents(thread.id()))
                .extracting(ThreadTurnEvent::id)
                .containsExactly("event-3", "event-1");
    }

    @Test
    void turnEventsUseStableTieBreakerForMatchingTimestamps()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        InMemoryTaskTurnEventStore turnEvents = new InMemoryTaskTurnEventStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turnEvents.appendEvent(turnEvent("event-a", "turn-1", thread.id(), now));
        turnEvents.appendEvent(turnEvent("event-c", "turn-1", thread.id(), now));
        turnEvents.appendEvent(turnEvent("event-b", "turn-1", thread.id(), now.minusSeconds(1)));
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                turnEvents,
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.turnEvents(thread.id()))
                .extracting(ThreadTurnEvent::id)
                .containsExactly("event-c", "event-a", "event-b");
    }

    @Test
    void activeTurnsReturnQueuedAndRunningOnly()
    {
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("queued", "thread-1", ThreadTurnStatus.QUEUED, now.minusSeconds(30)));
        turns.saveTurn(turn("completed", "thread-2", ThreadTurnStatus.COMPLETED, now.minusSeconds(20)));
        turns.saveTurn(turn("running", "thread-3", ThreadTurnStatus.RUNNING, now.minusSeconds(10)));
        ThreadService service = new ThreadService(
                new InMemoryTaskStore(),
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.activeTurns(50))
                .extracting(ThreadTurn::id)
                .containsExactly("queued", "running");
    }

    @Test
    void activeTurnsUnionLegacyAndTypedTurnsInOneOldestFirstPage()
    {
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("legacy-queued", "legacy-trunk",
                ThreadTurnStatus.QUEUED, now.minusSeconds(30)));
        turns.saveTurn(turn("legacy-complete", "legacy-trunk",
                ThreadTurnStatus.COMPLETED, now.minusSeconds(25)));
        V2ThreadControlService typed = Mockito.mock(V2ThreadControlService.class);
        Mockito.when(typed.activeTurns(50)).thenReturn(List.of(
                turn("typed-running", "typed-trunk",
                        ThreadTurnStatus.RUNNING, now.minusSeconds(20))));
        ThreadService service = new ThreadService(
                new InMemoryTaskStore(),
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2ThreadControls(typed);

        assertThat(service.activeTurns(50))
                .extracting(ThreadTurn::id)
                .containsExactly("legacy-queued", "typed-running");
        Mockito.verify(typed).activeTurns(50);
    }

    @Test
    void v2InterruptForwardsTheExactTrunkTurn()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        V2ThreadControlService typed = Mockito.mock(V2ThreadControlService.class);
        Thread trunk = thread("trunk-v2");
        Mockito.when(store.findThreadById(trunk.id()))
                .thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion(trunk.id()))
                .thenReturn(Optional.of("V2"));
        ThreadService service = new ThreadService(
                store, Mockito.mock(TaskStore.class),
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2ThreadControls(typed);

        service.interruptTrunk(trunk.id(), "turn-2");

        Mockito.verify(typed).interrupt(trunk.id(), "turn-2");
    }

    @Test
    void listByStatusReturnsEmptyForNonPositiveLimit()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread("thread-1"));
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.listByStatus(ThreadStatus.IDLE, 0)).isEmpty();
        assertThat(service.listByStatus(ThreadStatus.IDLE, -1)).isEmpty();
    }

    @Test
    void listByGroupReturnsEmptyForNonPositiveLimit()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        Thread thread = thread("thread-1");
        store.saveThread(thread);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(List.of(new ThreadGroupMembership(thread.id(), "group-1", now))),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.listByGroup("group-1", 0)).isEmpty();
        assertThat(service.listByGroup("group-1", -1)).isEmpty();
    }

    @Test
    void createGroupDeduplicatesInitialTaskIdsBeforeCapCheck()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread("thread-1"));
        store.saveThread(thread("thread-2"));
        EmptyTaskGroupStore groups = new EmptyTaskGroupStore();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                groups,
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        ThreadGroup group = service.createGroup(new ThreadService.NewGroupRequest(
                "Backend",
                "B",
                "blue",
                1,
                List.of("thread-1", "thread-1", "thread-2", "thread-2", "thread-2")));

        assertThat(groups.listMembers(group.id()))
                .extracting(ThreadGroupMembership::threadId)
                .containsExactly("thread-1", "thread-2");
    }

    @Test
    void createDeduplicatesInitialGroupIds()
    {
        EmptyTaskGroupStore groups = new EmptyTaskGroupStore();
        groups.saveGroup(group("group-1"));
        ThreadService service = new ThreadService(
                new InMemoryTaskStore(),
                new StubTaskStore(),
                groups,
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));
        enableV2Creation(service);

        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/work",
                "main",
                /* initialPrompt */ null,
                List.of("group-1", "group-1"),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));

        assertThat(groups.listMembers("group-1"))
                .extracting(ThreadGroupMembership::threadId)
                .containsExactly(thread.id());
    }

    @Test
    void legacyStopFailsClosedBeforeSchedulerOrRuntime()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.stop(thread.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void legacyStopDoesNotProbeForALiveSession()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.stop(thread.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void legacyDeleteFailsClosedAndKeepsHistory()
    {
        Thread thread = thread("thread-1", ThreadStatus.COMPLETED);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.delete(thread.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        assertThat(store.findThreadById(thread.id())).contains(thread);
    }

    @Test
    void v2DeleteProvesQuiescenceBeforeDeletingStoredData()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        TaskStore tasks = Mockito.mock(TaskStore.class);
        WorkspaceDataPurger dataPurger = Mockito.mock(WorkspaceDataPurger.class);
        V2ThreadControlService typed = Mockito.mock(V2ThreadControlService.class);
        Thread trunk = thread("trunk-v2", ThreadStatus.IDLE);
        V2ThreadControlService.DeletionPermit permit =
                new V2ThreadControlService.DeletionPermit("trunk-v2", 7);
        Mockito.when(store.findThreadById("trunk-v2"))
                .thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion("trunk-v2"))
                .thenReturn(Optional.of("V2"));
        Mockito.when(store.findPlanningSnapshot("trunk-v2"))
                .thenReturn(Optional.empty());
        Mockito.when(tasks.listTasksByThread("trunk-v2"))
                .thenReturn(List.of());
        Mockito.when(typed.prepareDeletion("trunk-v2")).thenReturn(permit);
        Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(typed).delete(Mockito.eq(permit), Mockito.any());
        ThreadService service = new ThreadService(
                store, tasks, Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                dataPurger);
        service.setV2ThreadControls(typed);

        service.delete("trunk-v2");

        InOrder order = Mockito.inOrder(typed, store);
        order.verify(typed).prepareDeletion("trunk-v2");
        order.verify(typed).delete(Mockito.eq(permit), Mockito.any());
        Mockito.verify(store).deleteThread("trunk-v2");
        Mockito.verify(dataPurger).purgeThreadScoped("trunk-v2", List.of());
    }

    @Test
    void v2DeleteRejectsBeforeStoppingAnyLegacyRuntime()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        TaskStore tasks = Mockito.mock(TaskStore.class);
        V2ThreadControlService typed = Mockito.mock(V2ThreadControlService.class);
        Thread trunk = thread("trunk-v2", ThreadStatus.IDLE);
        Mockito.when(store.findThreadById("trunk-v2"))
                .thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion("trunk-v2"))
                .thenReturn(Optional.of("V2"));
        Mockito.when(tasks.listTasksByThread("trunk-v2"))
                .thenReturn(List.of());
        Mockito.when(typed.prepareDeletion("trunk-v2"))
                .thenThrow(new IllegalStateException("one typed wait is open"));
        ThreadService service = new ThreadService(
                store, tasks, Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2ThreadControls(typed);

        assertThatThrownBy(() -> service.delete("trunk-v2"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(
                        ((ResponseStatusException) failure).getStatusCode().value())
                        .isEqualTo(409));
        Mockito.verify(store, Mockito.never()).deleteThread(Mockito.anyString());
    }

    @Test
    void materialiseTaskFailsClosedWithoutV2Creation()
    {
        ThreadStore store = Mockito.mock(ThreadStore.class);
        TaskStore tasks = Mockito.mock(TaskStore.class);
        WorktreeService worktrees = Mockito.mock(WorktreeService.class);
        Thread trunk = thread("trunk");
        Mockito.when(store.findThreadById(trunk.id()))
                .thenReturn(Optional.of(trunk));
        Mockito.when(store.findTurnVersion(trunk.id()))
                .thenReturn(Optional.of("V2"));
        ThreadService service = new ThreadService(
                store,
                tasks,
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                new GitRunner(),
                worktrees,
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6",
                "Fix tests", "/tmp/repo", "main", "please fix", List.of(),
                "DEVELOP", null, null, null, trunk.workspaceId(), null);

        assertThatThrownBy(() -> service.materialiseTask(trunk.id(), request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(
                        ((ResponseStatusException) failure).getStatusCode().value())
                        .isEqualTo(503))
                .hasMessageContaining("V2 Task creation is not configured");
    }

    @Test
    void legacyDeleteDoesNotRemoveTaskWorktree()
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        // Seed a completed task with the worktree path the test
        // expects to be pruned. {@link ThreadService#delete} now refuses
        // unless every task has reached COMPLETED; an idle task here
        // would correctly trigger the new pre-flight check instead of
        // exercising the worktree-reaper path this test cares about.
        SingleTaskStore tasks = new SingleTaskStore(new Task(
                "task-1", thread.id(), 1L, TaskStatus.COMPLETED,
                "dev/thread-1",
                "/tmp/work/.bytequay/worktrees/dev/thread-1",
                "main", "/tmp/work",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null));
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.empty());
        ThreadService service = new ThreadService(
                store,
                tasks,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new GitRunner(),
                worktrees,
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.delete(thread.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        assertThat(worktrees.removeRequests).isEmpty();
        assertThat(store.findThreadById(thread.id())).contains(thread);
    }

    @Test
    void threadDiffAndCommitViewsUseAgentCwd(@TempDir Path tmp)
            throws IOException
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        // The commit/diff surfaces skip git when the worktree dir is gone
        // (a merged task's worktree gets reaped), so the seeded agentCwd
        // must be a real, present directory for this "worktree alive" case.
        Path worktree = tmp.resolve(".bytequay/worktrees/dev/thread-1");
        Files.createDirectories(worktree);
        // Per-task fields live on the active task projection; seed
        // one so service.X(threadId) can resolve a real agentCwd.
        Task active = new Task(
                "task-1", thread.id(), 1L, TaskStatus.IDLE,
                "dev/thread-1",
                worktree.toString(),
                "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        SingleTaskStore tasks = new SingleTaskStore(active);
        RecordingGitRunner git = new RecordingGitRunner();
        ThreadService service = new ThreadService(
                store,
                tasks,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                git,
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class));

        service.listWorkingChanges(thread.id(), null);
        service.getWorkingDiff(thread.id(), null, "src/App.java");
        service.listTaskCommits(thread.id(), null);
        service.listCommitFiles(thread.id(), null, "abc123");
        service.getCommitDiff(thread.id(), null, "abc123", "src/App.java");

        Path expected = Path.of(active.agentCwd());
        assertThat(git.workingTreeFilesPaths).containsExactly(expected);
        assertThat(git.workingTreeDiffPaths).containsExactly(expected);
        assertThat(git.listCommitsAheadPaths).containsExactly(expected);
        // Commits are scoped to the task's base branch (base..HEAD), not a
        // time window — so the panel shows only the task's own commits.
        assertThat(git.listCommitsAheadBases).containsExactly("main");
        assertThat(git.commitFilesPaths).containsExactly(expected);
        assertThat(git.commitDiffPaths).containsExactly(expected);
    }

    @Test
    void v2ThreadDiffUsesTypedWorktreeProjection(@TempDir Path tmp)
            throws IOException
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        Path worktree = tmp.resolve(".bytequay/worktrees/dev/thread-1");
        Files.createDirectories(worktree);
        Task raw = new Task(
                "task-1", thread.id(), 1L, TaskStatus.IDLE,
                null, null, null, null,
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null,
                null, null, null);
        Task projected = new Task(
                raw.id(), raw.threadId(), raw.seq(), raw.status(),
                "dev/thread-1", worktree.toString(), "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, raw.createdAt(), null, null,
                null, null, null);
        TaskStore tasks = Mockito.mock(TaskStore.class);
        Mockito.when(tasks.findTaskById(raw.id())).thenReturn(Optional.of(raw));
        Mockito.when(tasks.isV2Task(raw.id())).thenReturn(true);
        V2DevelopmentFlowProjection projection =
                Mockito.mock(V2DevelopmentFlowProjection.class);
        Mockito.when(projection.project(raw)).thenReturn(projected);
        RecordingGitRunner git = new RecordingGitRunner();
        ThreadService service = new ThreadService(
                store, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(),
                git, noopWorktreeService(), stubIdGenerator(),
                Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
        service.setV2TaskProjection(projection);

        service.listWorkingChanges(thread.id(), raw.id());

        assertThat(git.workingTreeFilesPaths).containsExactly(worktree);
        Mockito.verify(projection).project(raw);
    }

    @Test
    void threadDiffRejectsExplicitTaskFromAnotherTrunk()
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        Task other = new Task(
                "task-other", "thread-2", 1L, TaskStatus.IDLE,
                "dev/other", "/tmp/other", "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null,
                null, null, null);
        TaskStore tasks = Mockito.mock(TaskStore.class);
        Mockito.when(tasks.findTaskById(other.id())).thenReturn(Optional.of(other));
        ThreadService service = new ThreadService(
                store, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(),
                new GitRunner(), noopWorktreeService(), stubIdGenerator(),
                Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));

        assertThatThrownBy(() -> service.listWorkingChanges(
                thread.id(), other.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void commitAndDiffViewsReturnEmptyWhenTheWorktreeWasReaped(@TempDir Path tmp)
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        // A merged task's worktree is deleted by the lifecycle driver, so
        // its agentCwd no longer exists on disk — the read surfaces must
        // return nothing rather than 500 on a missing directory.
        Path reaped = tmp.resolve("reaped/dev/thread-1");
        Task active = new Task(
                "task-1", thread.id(), 1L, TaskStatus.COMPLETED,
                "dev/thread-1",
                reaped.toString(),
                "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        SingleTaskStore tasks = new SingleTaskStore(active);
        RecordingGitRunner git = new RecordingGitRunner();
        ThreadService service = new ThreadService(
                store, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(), git, noopWorktreeService(), stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.listTaskCommits(thread.id(), null)).isEmpty();
        assertThat(service.getWorkingDiff(thread.id(), null, "src/App.java")).isEmpty();
        // git was never invoked against the missing directory.
        assertThat(git.listCommitsAheadPaths).isEmpty();
        assertThat(git.workingTreeDiffPaths).isEmpty();
    }

    @Test
    void cumulativeDiffMapsStatusWordsAndCountsLinesFromPatch(@TempDir Path tmp)
            throws IOException
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        Path worktree = tmp.resolve(".bytequay/worktrees/dev/thread-1");
        Files.createDirectories(worktree);
        Task active = new Task(
                "task-1", thread.id(), 1L, TaskStatus.IDLE,
                "dev/thread-1",
                worktree.toString(),
                "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        SingleTaskStore tasks = new SingleTaskStore(active);
        RecordingGitRunner git = new RecordingGitRunner();
        // A range listing reports A/M letters and 0 counts; the per-file
        // patch carries the real lines the service must count.
        git.cannedRangeFiles = List.of(
                new GitRunner.CommitFileChange("src/New.java", "A", 0, 0),
                new GitRunner.CommitFileChange("src/Old.java", "M", 0, 0));
        String newPatch = "@@\n+x\n+x\n-y\n";
        // +++/--- headers must NOT count as add/del lines.
        String oldPatch = "--- a/src/Old.java\n+++ b/src/Old.java\n@@\n+keep\n-drop\n";
        git.cannedRangePatches = Map.of("src/New.java", newPatch, "src/Old.java", oldPatch);
        ThreadService service = new ThreadService(
                store, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(), git, noopWorktreeService(), stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));

        List<ThreadService.TaskDiffFile> diff = service.taskCumulativeDiff(thread.id(), null);

        assertThat(diff).hasSize(2);
        assertThat(diff.get(0))
                .extracting(
                        ThreadService.TaskDiffFile::filename,
                        ThreadService.TaskDiffFile::status,
                        ThreadService.TaskDiffFile::additions,
                        ThreadService.TaskDiffFile::deletions,
                        ThreadService.TaskDiffFile::patch)
                .containsExactly("src/New.java", "added", 2, 1, newPatch);
        assertThat(diff.get(1))
                .extracting(
                        ThreadService.TaskDiffFile::filename,
                        ThreadService.TaskDiffFile::status,
                        ThreadService.TaskDiffFile::additions,
                        ThreadService.TaskDiffFile::deletions)
                .containsExactly("src/Old.java", "modified", 1, 1);
    }

    @Test
    void cumulativeDiffReturnsEmptyWhenTheWorktreeWasReaped(@TempDir Path tmp)
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        Path reaped = tmp.resolve("reaped/dev/thread-1");
        Task active = new Task(
                "task-1", thread.id(), 1L, TaskStatus.COMPLETED,
                "dev/thread-1",
                reaped.toString(),
                "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        SingleTaskStore tasks = new SingleTaskStore(active);
        RecordingGitRunner git = new RecordingGitRunner();
        git.cannedRangeFiles = List.of(
                new GitRunner.CommitFileChange("src/New.java", "A", 0, 0));
        ThreadService service = new ThreadService(
                store, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(), git, noopWorktreeService(), stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));

        assertThat(service.taskCumulativeDiff(thread.id(), null)).isEmpty();
    }

    private record WorktreeCreateRequest(
            Path repoRoot, String sessionId, String title, String baseSha) {}

    private record WorktreeRemoveRequest(Path repoRoot, String worktreePath, String localBranch) {}

    private static WorktreeService noopWorktreeService()
    {
        return new RecordingWorktreeService(Optional.empty());
    }

    private static void enableV2Creation(ThreadService service)
    {
        V2TaskCreationService creation = Mockito.mock(V2TaskCreationService.class);
        Mockito.when(creation.routes(Mockito.anyString())).thenReturn(true);
        service.setV2TaskCreation(creation);
    }

    /** IdGenerator backed by a tiny in-memory sequence store. Hand-rolled
     *  rather than mocked because we just need monotonic counters per
     *  ymd — the format itself is covered by TestIdGenerator. */
    private static IdGenerator stubIdGenerator()
    {
        IdSequenceStore store = new IdSequenceStore() {
            private final Map<String, Integer> next = new LinkedHashMap<>();

            @Override
            public int nextThreadSeq(String ymd)
            {
                int v = next.getOrDefault(ymd, 1);
                next.put(ymd, v + 1);
                return v;
            }
        };
        return new IdGenerator(store);
    }

    private static final class RecordingWorktreeService
            extends WorktreeService
    {
        private final Optional<WorktreeHandle> createResult;
        private final List<WorktreeCreateRequest> createRequests = new ArrayList<>();
        private final List<WorktreeRemoveRequest> removeRequests = new ArrayList<>();

        private RecordingWorktreeService(Optional<WorktreeHandle> createResult)
        {
            super(new GitRunner(), Mockito.mock(WatchedRepoStore.class));
            this.createResult = createResult;
        }

        @Override
        public Optional<WorktreeHandle> create(
                Path repoRoot, String sessionId, String title, String baseSha)
        {
            createRequests.add(new WorktreeCreateRequest(repoRoot, sessionId, title, baseSha));
            return createResult;
        }

        @Override
        public void remove(Path repoRoot, String worktreePath, String localBranch)
        {
            removeRequests.add(new WorktreeRemoveRequest(repoRoot, worktreePath, localBranch));
        }

        @Override
        public void removePlanningWorktree(Path repoRoot, String threadId)
        {
        }
    }

    private static final class RecordingGitRunner
            extends GitRunner
    {
        private final List<Path> workingTreeFilesPaths = new ArrayList<>();
        private final List<Path> workingTreeDiffPaths = new ArrayList<>();
        private final List<Path> listCommitsAheadPaths = new ArrayList<>();
        private final List<String> listCommitsAheadBases = new ArrayList<>();
        private final List<Path> commitFilesPaths = new ArrayList<>();
        private final List<Path> commitDiffPaths = new ArrayList<>();
        // Canned returns for the cumulative-diff path; empty by default so
        // the existing commit-view tests are unaffected.
        private List<GitRunner.CommitFileChange> cannedRangeFiles = List.of();
        private Map<String, String> cannedRangePatches = Map.of();
        private List<GitRunner.CommitFileChange> cannedCommitFiles = List.of();
        private Map<String, String> cannedCommitPatches = Map.of();

        @Override
        public List<GitRunner.WorkingTreeFile> workingTreeFiles(Path workingDir)
        {
            workingTreeFilesPaths.add(workingDir);
            return List.of();
        }

        @Override
        public String workingTreeFileDiff(Path workingDir, String path, int maxBytes)
        {
            workingTreeDiffPaths.add(workingDir);
            return "";
        }

        @Override
        public boolean refExists(Path workingDir, String ref)
        {
            // The commit surface resolves its base ref before diffing; the
            // configured "main" is treated as present so listCommitsAhead is
            // exercised with it.
            return "main".equals(ref);
        }

        @Override
        public Optional<String> mergeBase(Path workingDir, String branch, String base)
        {
            // No history in this stub, so the resolver falls back to the
            // base ref name itself.
            return Optional.empty();
        }

        @Override
        public List<GitRunner.CommitEntry> listCommitsAhead(Path workingDir, String base, int limit)
        {
            listCommitsAheadPaths.add(workingDir);
            listCommitsAheadBases.add(base);
            return List.of();
        }

        @Override
        public List<GitRunner.CommitFileChange> commitFiles(Path workingDir, String sha)
        {
            commitFilesPaths.add(workingDir);
            return cannedCommitFiles;
        }

        @Override
        public String commitFileDiff(Path workingDir, String sha, String path, int maxBytes)
        {
            commitDiffPaths.add(workingDir);
            return cannedCommitPatches.getOrDefault(path, "");
        }

        @Override
        public List<GitRunner.CommitFileChange> rangeFiles(Path workingDir, String base, String head)
        {
            return cannedRangeFiles;
        }

        @Override
        public String rangeFileDiff(Path workingDir, String base, String head, String path, int maxBytes)
        {
            return cannedRangePatches.getOrDefault(path, "");
        }

        @Override
        public List<GitRunner.CommitFileChange> effectiveFiles(Path workingDir, String base)
        {
            return cannedRangeFiles;
        }

        @Override
        public String effectiveFileDiff(Path workingDir, String base, String path, int maxBytes)
        {
            return cannedRangePatches.getOrDefault(path, "");
        }
    }

    private static final class EmptyTaskGroupStore
            implements ThreadGroupStore
    {
        private final Map<String, ThreadGroup> groups = new LinkedHashMap<>();
        private final List<ThreadGroupMembership> memberships = new ArrayList<>();

        private EmptyTaskGroupStore()
        {
            this(List.of());
        }

        private EmptyTaskGroupStore(List<ThreadGroupMembership> memberships)
        {
            this.memberships.addAll(memberships);
        }

        @Override
        public void saveGroup(ThreadGroup group)
        {
            groups.put(group.id(), group);
        }

        @Override
        public Optional<ThreadGroup> findGroupById(String id)
        {
            return Optional.ofNullable(groups.get(id));
        }

        @Override
        public List<ThreadGroup> listGroups()
        {
            return List.copyOf(groups.values());
        }

        @Override
        public void deleteGroup(String id)
        {
            groups.remove(id);
            memberships.removeIf(membership -> membership.groupId().equals(id));
        }

        @Override
        public void addMember(String threadId, String groupId)
        {
            boolean exists = memberships.stream()
                    .anyMatch(membership -> membership.threadId().equals(threadId)
                            && membership.groupId().equals(groupId));
            if (!exists) {
                memberships.add(new ThreadGroupMembership(threadId, groupId, Instant.EPOCH));
            }
        }

        @Override
        public void removeMember(String threadId, String groupId)
        {
            memberships.removeIf(membership -> membership.threadId().equals(threadId)
                    && membership.groupId().equals(groupId));
        }

        @Override
        public List<ThreadGroupMembership> listMembers(String groupId)
        {
            return memberships.stream()
                    .filter(membership -> membership.groupId().equals(groupId))
                    .toList();
        }

        @Override
        public List<ThreadGroupMembership> listMemberships(String threadId)
        {
            return memberships.stream()
                    .filter(membership -> membership.threadId().equals(threadId))
                    .toList();
        }

        @Override
        public List<ThreadGroupMembership> listAllMemberships()
        {
            return memberships;
        }

        @Override
        public long countMembers(String groupId)
        {
            return listMembers(groupId).size();
        }
    }

    /** Empty TaskStore — these scheduler tests don't exercise the
     *  per-work-unit storage that landed alongside Thread/Task split.
     *  A returning-empty stub keeps the constructor happy. */
    private static final class StubTaskStore
            implements TaskStore
    {
        @Override public void saveTask(Task task) {}
        @Override public Optional<Task> findTaskById(String id) { return Optional.empty(); }
        @Override public void deleteTask(String id) {}
        @Override public List<Task> listTasksByThread(String threadId) { return List.of(); }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) { return List.of(); }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) { return Optional.empty(); }
        @Override public Optional<Long> maxSeqForThread(String threadId) { return Optional.empty(); }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    /** TaskStore that holds one task which is NOT "active" — {@link
     *  #findActiveTaskForThread} returns empty (mirroring a task parked at
     *  AWAITING_REVIEW / phase-complete) while {@link
     *  #findLatestTaskForThread} still surfaces it. Lets a test assert that
     *  {@code send} binds a task-window turn to the latest task rather than
     *  falling back to a trunk turn. */
    private static final class LatestOnlyTaskStore
            implements TaskStore
    {
        private final Task task;

        LatestOnlyTaskStore(Task task) { this.task = task; }

        @Override public void saveTask(Task t) {}
        @Override public Optional<Task> findTaskById(String id) {
            return task.id().equals(id) ? Optional.of(task) : Optional.empty();
        }
        @Override public void deleteTask(String id) {}
        @Override public List<Task> listTasksByThread(String threadId) {
            return task.threadId().equals(threadId) ? List.of(task) : List.of();
        }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) {
            return List.of();
        }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) {
            return task.threadId().equals(threadId) ? Optional.of(task) : Optional.empty();
        }
        @Override public Optional<Long> maxSeqForThread(String threadId) {
            return Optional.of(task.seq());
        }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    /** TaskStore that records saveTask calls and surfaces them back
     *  through the standard query API. Tests that exercise the create
     *  flow need this so the active-task projection lands on the
     *  Thread record read back from the store. */
    private static final class InMemoryRecordingTaskStore
            implements TaskStore
    {
        final Map<String, Task> byId = new LinkedHashMap<>();

        @Override public void saveTask(Task task) { byId.put(task.id(), task); }
        @Override public Optional<Task> findTaskById(String id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override public void deleteTask(String id) { byId.remove(id); }
        @Override public List<Task> listTasksByThread(String threadId) {
            return byId.values().stream().filter(t -> t.threadId().equals(threadId)).toList();
        }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) {
            return byId.values().stream()
                    .filter(t -> t.threadId().equals(threadId))
                    .sorted(Comparator.comparingLong(Task::seq).reversed())
                    .toList();
        }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) {
            return byId.values().stream()
                    .filter(t -> t.threadId().equals(threadId))
                    .max(Comparator.comparingLong(Task::seq));
        }
        @Override public Optional<Long> maxSeqForThread(String threadId) {
            return findLatestTaskForThread(threadId).map(Task::seq);
        }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    /** Plain ThreadStore delegate. Per-task fields no longer live on
     *  Thread, so the create-flow tests look the active task up via the
     *  TaskStore directly. */
    private static final class ProjectingThreadStore
            implements ThreadStore
    {
        private final ThreadStore inner;

        ProjectingThreadStore(ThreadStore inner)
        {
            this.inner = inner;
        }

        @Override public void saveThread(Thread thread) { inner.saveThread(thread); }
        @Override public Optional<Thread> findThreadById(String id) {
            return inner.findThreadById(id);
        }
        @Override public Optional<PlanningSnapshot> findPlanningSnapshot(String threadId) {
            return inner.findPlanningSnapshot(threadId);
        }
        @Override public void setPlanningSnapshot(String threadId, PlanningSnapshot snapshot) {
            inner.setPlanningSnapshot(threadId, snapshot);
        }
        @Override public List<Thread> listTasksByStatus(ThreadStatus status, int limit) {
            return inner.listTasksByStatus(status, limit);
        }
        @Override public List<Thread> listTasksByIds(Collection<String> ids) {
            return inner.listTasksByIds(ids);
        }
        @Override public List<Thread> listThreadsUpdatedSince(Instant since) {
            return inner.listThreadsUpdatedSince(since);
        }
        @Override public void deleteThread(String threadId) { inner.deleteThread(threadId); }
        @Override public void appendMessage(ThreadMessage message) { inner.appendMessage(message); }
        @Override public List<ThreadMessage> listMessages(String threadId) {
            return inner.listMessages(threadId);
        }
        @Override public void recordFile(ThreadFile file) { inner.recordFile(file); }
        @Override public List<ThreadFile> listFiles(String threadId) { return inner.listFiles(threadId); }
    }

    /** TaskStore that holds exactly one seeded task. The bridge
     *  teardown moved per-task fields off Thread, so tests that need
     *  thread.activeTask() to be non-null seed via this helper. */
    private static final class SingleTaskStore
            implements TaskStore
    {
        private final Task task;

        SingleTaskStore(Task task) { this.task = task; }

        @Override public void saveTask(Task t) {}
        @Override public Optional<Task> findTaskById(String id) {
            return task.id().equals(id) ? Optional.of(task) : Optional.empty();
        }
        @Override public void deleteTask(String id) {}
        @Override public List<Task> listTasksByThread(String threadId) {
            return task.threadId().equals(threadId) ? List.of(task) : List.of();
        }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) {
            return task.threadId().equals(threadId) ? List.of(task) : List.of();
        }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) {
            return task.threadId().equals(threadId) ? Optional.of(task) : Optional.empty();
        }
        @Override public Optional<Long> maxSeqForThread(String threadId) {
            return task.threadId().equals(threadId) ? Optional.of(task.seq()) : Optional.empty();
        }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    private static final class InMemoryTaskStore
            implements ThreadStore
    {
        private final Map<String, Thread> threads = new LinkedHashMap<>();
        private final Map<String, PlanningSnapshot> planningSnapshots = new LinkedHashMap<>();

        @Override
        public void saveThread(Thread thread)
        {
            threads.put(thread.id(), thread);
        }

        @Override
        public Optional<Thread> findThreadById(String id)
        {
            return Optional.ofNullable(threads.get(id));
        }

        @Override
        public Optional<PlanningSnapshot> findPlanningSnapshot(String threadId)
        {
            return Optional.ofNullable(planningSnapshots.get(threadId));
        }

        @Override
        public void setPlanningSnapshot(String threadId, PlanningSnapshot snapshot)
        {
            planningSnapshots.put(threadId, snapshot);
        }

        @Override
        public void deleteThread(String id)
        {
            threads.remove(id);
        }

        @Override
        public List<Thread> listTasksByStatus(ThreadStatus status, int limit)
        {
            return threads.values().stream()
                    .filter(thread -> thread.status() == status)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<Thread> listTasksByIds(Collection<String> ids)
        {
            return threads.values().stream()
                    .filter(thread -> ids.contains(thread.id()))
                    .toList();
        }

        @Override
        public List<Thread> listThreadsUpdatedSince(Instant since)
        {
            return threads.values().stream()
                    .filter(thread -> !thread.updatedAt().isBefore(since))
                    .toList();
        }

        @Override
        public List<Thread> listThreadsByWorkspace(String workspaceId)
        {
            return threads.values().stream()
                    .filter(thread -> thread.workspaceId().equals(workspaceId))
                    .toList();
        }

        @Override
        public void appendMessage(ThreadMessage message) {}

        @Override
        public List<ThreadMessage> listMessages(String threadId)
        {
            return List.of();
        }

        @Override
        public void recordFile(ThreadFile file) {}

        @Override
        public List<ThreadFile> listFiles(String threadId)
        {
            return List.of();
        }
    }

    private static final class InMemoryTaskTurnStore
            implements ThreadTurnStore
    {
        private final Map<String, ThreadTurn> turns = new LinkedHashMap<>();

        @Override
        public void saveTurn(ThreadTurn turn)
        {
            turns.put(turn.id(), turn);
        }

        @Override
        public Optional<ThreadTurn> findTurnById(String id)
        {
            return Optional.ofNullable(turns.get(id));
        }

        @Override
        public List<ThreadTurn> listTurnsByStatus(ThreadTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.status() == status)
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByStatusAfter(ThreadTurnStatus status, Instant createdAfter, String idAfter, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.status() == status)
                    .filter(turn -> turn.createdAt().compareTo(createdAfter) > 0
                            || (turn.createdAt().equals(createdAfter) && turn.id().compareTo(idAfter) > 0))
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByStatuses(Collection<ThreadTurnStatus> statuses, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> statuses.contains(turn.status()))
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByTaskIdAndStatus(String threadId, ThreadTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.threadId().equals(threadId))
                    .filter(turn -> turn.status() == status)
                    .sorted(threadHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByTaskId(String threadId, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.threadId().equals(threadId))
                    .sorted(threadHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean hasOtherActiveTurn(String agentRunId, String excludingTurnId)
        {
            return turns.values().stream()
                    .filter(turn -> agentRunId.equals(turn.agentRunId()))
                    .filter(turn -> !excludingTurnId.equals(turn.id()))
                    .anyMatch(turn -> turn.status() == ThreadTurnStatus.QUEUED
                            || turn.status() == ThreadTurnStatus.RUNNING);
        }

        private static Comparator<ThreadTurn> turnOrder()
        {
            return Comparator.comparing(ThreadTurn::createdAt)
                    .thenComparing(ThreadTurn::id);
        }

        private static Comparator<ThreadTurn> threadHistoryOrder()
        {
            return Comparator.comparing(ThreadTurn::createdAt)
                    .thenComparing(ThreadTurn::id)
                    .reversed();
        }
    }

    private static final class InMemoryTaskTurnEventStore
            implements ThreadTurnEventStore
    {
        private final Map<String, ThreadTurnEvent> events = new LinkedHashMap<>();

        @Override
        public void appendEvent(ThreadTurnEvent event)
        {
            events.put(event.id(), event);
        }

        @Override
        public List<ThreadTurnEvent> listEventsByTaskId(String threadId, int limit)
        {
            return events.values().stream()
                    .filter(event -> event.threadId().equals(threadId))
                    .sorted(eventHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        private static Comparator<ThreadTurnEvent> eventHistoryOrder()
        {
            return Comparator.comparing(ThreadTurnEvent::createdAt)
                    .thenComparing(ThreadTurnEvent::id)
                    .reversed();
        }
    }

    private static ThreadTurn turn(String id, String threadId, Instant createdAt)
    {
        return turn(id, threadId, ThreadTurnStatus.QUEUED, createdAt);
    }

    private static ThreadTurn turn(String id, String threadId, ThreadTurnStatus status, Instant createdAt)
    {
        return new ThreadTurn(
                id,
                threadId,
                /* taskId */ null,
                ThreadResourceLane.CLI,
                status,
                "input",
                createdAt,
                createdAt,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null,
                TurnInitiator.user(), null, ThreadScope.TRUNK);
    }

    private static ThreadTurnEvent turnEvent(String id, String turnId, String threadId, Instant createdAt)
    {
        return new ThreadTurnEvent(
                id,
                turnId,
                threadId,
                /* taskId */ null,
                ThreadTurnEventType.TURN_QUEUED,
                createdAt,
                /* message */ null);
    }

    private static ThreadGroup group(String id)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new ThreadGroup(id, "Group " + id, "G", "blue", 1, now, now);
    }

    private static ThreadService service(
            ThreadStore store, ThreadGroupStore groups)
    {
        return new ThreadService(
                store, Mockito.mock(TaskStore.class), groups,
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class), new GitRunner(),
                noopWorktreeService(),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class));
    }

    private static Thread copyThread(
            Thread thread, ThreadStatus status, Instant updatedAt)
    {
        return new Thread(
                thread.id(), thread.kind(), thread.provider(),
                thread.agentSessionId(), thread.title(), status, thread.model(),
                thread.costUsdMilli(), thread.tokensIn(), thread.tokensOut(),
                thread.createdAt(), updatedAt, thread.endedAt(),
                thread.errorMessage(), thread.flow(), thread.workspaceId(),
                thread.workModel(), thread.parentReviewPassId(),
                thread.parallelSlots(), thread.parentTaskId(), thread.prRef(),
                thread.description());
    }

    private static Thread thread()
    {
        return thread("thread-1");
    }

    private static Thread thread(String id)
    {
        return thread(id, ThreadStatus.IDLE);
    }

    private static Thread thread(String id, ThreadStatus status)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new Thread(
                id,
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Fix tests",
                status,
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
    }

    private static Thread threadWithWorktree(String id)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new Thread(
                id,
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Fix tests",
                ThreadStatus.COMPLETED,
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ now,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
    }
}
