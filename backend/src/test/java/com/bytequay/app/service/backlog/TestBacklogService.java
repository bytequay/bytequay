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
package com.bytequay.app.service.backlog;

import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.BacklogStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.distillation.DistillationSignalServiceImpl;
import com.bytequay.app.service.threads.ThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBacklogService
{
    private static final Instant NOW = Instant.parse("2026-06-24T09:00:00Z");

    private BacklogStore store;
    private ThreadService threadService;
    private ThreadStore threadStore;
    private TaskStore taskStore;
    private DistillationSignalServiceImpl distillation;
    private BacklogServiceImpl service;

    @BeforeEach
    void setUp()
    {
        store = mock(BacklogStore.class);
        threadService = mock(ThreadService.class);
        threadStore = mock(ThreadStore.class);
        taskStore = mock(TaskStore.class);
        distillation = mock(DistillationSignalServiceImpl.class);
        service = new BacklogServiceImpl(store, threadService, threadStore, taskStore, distillation);
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createPersistsAFreshItem()
    {
        Thread trunk = mock(Thread.class);
        when(trunk.workspaceId()).thenReturn("ws-1");
        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(trunk));

        BacklogItem saved = service.create(
                "thread-1", "Add a cost meter", "body text", List.of("ui", "quality-scan"), null);

        ArgumentCaptor<BacklogItem> captor = ArgumentCaptor.forClass(BacklogItem.class);
        verify(store).save(captor.capture());
        BacklogItem persisted = captor.getValue();
        assertThat(persisted.threadId()).isEqualTo("thread-1");
        assertThat(persisted.title()).isEqualTo("Add a cost meter");
        assertThat(persisted.body()).isEqualTo("body text");
        assertThat(persisted.tags()).containsExactly("ui", "quality-scan");
        assertThat(persisted.startedAt()).isNull();
        assertThat(persisted.linkedTaskId()).isNull();
        assertThat(persisted.id()).isNotBlank();
        // A manual create lands at the head of the lifecycle with the default
        // priority when none was supplied.
        assertThat(persisted.status()).isEqualTo("open");
        assertThat(persisted.source()).isEqualTo("manual");
        assertThat(persisted.createdBy()).isEqualTo("user");
        assertThat(persisted.origin()).isEqualTo("user");
        assertThat(persisted.priority()).isEqualTo("medium");
        assertThat(saved).isSameAs(persisted);
    }

    @Test
    void createRejectsABlankTitle()
    {
        assertThatThrownBy(() -> service.create("thread-1", "  ", "b", List.of(), null))
                .isInstanceOf(ResponseStatusException.class);
        verify(store, never()).save(any());
    }

    @Test
    void updateLeavesNullFieldsUnchanged()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));

        service.update("b1", null, "new body", null, null);

        ArgumentCaptor<BacklogItem> captor = ArgumentCaptor.forClass(BacklogItem.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("Title");
        assertThat(captor.getValue().body()).isEqualTo("new body");
        assertThat(captor.getValue().tags()).containsExactly("ui");
    }

    @Test
    void updateOnUnknownIdIs404()
    {
        when(store.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("missing", "x", null, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void startDevelopmentPostsToTrunkAndMarksInProgress()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));
        when(threadService.sendTrunk(eq("thread-1"), any())).thenReturn("turn-1");

        BacklogServiceImpl.StartResult result = service.startDevelopment("b1");

        // The item content is posted into the trunk as a planning prompt; no
        // task is cut here. The prompt carries the backlog id plus the
        // confidence-or-confirm contract the trunk must follow before cutting.
        ArgumentCaptor<BacklogItem> saved = ArgumentCaptor.forClass(BacklogItem.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(store, threadService);
        order.verify(store).save(saved.capture());
        order.verify(threadService).sendTrunk(eq("thread-1"), prompt.capture());
        assertThat(saved.getValue().status()).isEqualTo(BacklogItem.STATUS_IN_PROGRESS);
        assertThat(prompt.getValue())
                .contains("(backlog item b1")
                .contains("backlog_item_id=b1")
                .contains("If the direction is clear and you are confident")
                .contains("If any important direction is uncertain")
                .contains("ask_user_question")
                .contains("confirm/approve the task direction")
                .contains("Title\n\nBody");
        verify(threadService, never()).materialiseTask(any(), any());
        assertThat(result.taskId()).isNull();
        assertThat(result.item().status()).isEqualTo("in-progress");
        assertThat(result.item().inProgressAt()).isNotNull();
        verify(distillation).record(
                eq("backlog-start"), eq("b1"), eq("started"), any(), any(), any(), any());
    }

    @Test
    void startDevelopmentStaysInProgressWhenTrunkDispatchFails()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));
        when(threadService.sendTrunk(eq("thread-1"), any()))
                .thenThrow(new IllegalStateException("scheduler unavailable"));

        assertThatThrownBy(() -> service.startDevelopment("b1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduler unavailable");

        ArgumentCaptor<BacklogItem> saved = ArgumentCaptor.forClass(BacklogItem.class);
        InOrder order = inOrder(store, threadService);
        order.verify(store).save(saved.capture());
        order.verify(threadService).sendTrunk(eq("thread-1"), any());
        assertThat(saved.getValue().status()).isEqualTo(BacklogItem.STATUS_IN_PROGRESS);
        verify(distillation, never()).record(
                eq("backlog-start"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void startDevelopmentOnANonCreatedItemIs409()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", true, "task-1")));
        assertThatThrownBy(() -> service.startDevelopment("b1"))
                .isInstanceOf(ResponseStatusException.class);
        verify(threadService, never()).sendTrunk(any(), any());
    }

    @Test
    void cancelExplorationRestoresAnInProgressItemToOpen()
    {
        when(store.findById("b1")).thenReturn(
                Optional.of(item("b1", false, null).markInProgress(NOW)));

        BacklogItem restored = service.cancelExploration("b1");

        assertThat(restored.status()).isEqualTo("open");
        verify(distillation).record(
                eq("backlog-cancel-exploration"), eq("b1"), eq("cancelled"), any(), any(), any(), any());
    }

    @Test
    void cancelExplorationOnANonInProgressItemIs409()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));
        assertThatThrownBy(() -> service.cancelExploration("b1"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listDelegatesToTheStore()
    {
        BacklogItem i = item("b1", false, null);
        when(store.findByThread("thread-1")).thenReturn(List.of(i));
        assertThat(service.list("thread-1")).containsExactly(i);
    }

    @Test
    void createBatchCreatesACrossLinkedAgentGroup()
    {
        List<BacklogServiceImpl.NewBacklogItem> inputs = List.of(
                new BacklogServiceImpl.NewBacklogItem(
                        "Clean A", "Useful summary.\n\nImplementation detail.", List.of("ui"), "high"),
                new BacklogServiceImpl.NewBacklogItem("Clean B", "", null, null));

        BacklogServiceImpl.BatchResult result = service.createBatch("thread-1", inputs);

        assertThat(result.backlogItemIds()).hasSize(2);
        assertThat(result.relatedBacklogGroupId()).isNotBlank();

        ArgumentCaptor<BacklogItem> captor = ArgumentCaptor.forClass(BacklogItem.class);
        verify(store, times(2)).save(captor.capture());
        List<BacklogItem> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(it -> {
            assertThat(it.source()).isEqualTo("agent");
            assertThat(it.createdBy()).isEqualTo("trunk-agent");
            assertThat(it.origin()).isEqualTo("agent");
            assertThat(it.status()).isEqualTo("open");
        });
        // Each item references its sibling; priority is carried through.
        assertThat(saved.get(0).relatedBacklogIds()).containsExactly(saved.get(1).id());
        assertThat(saved.get(1).relatedBacklogIds()).containsExactly(saved.get(0).id());
        assertThat(saved.get(0).priority()).isEqualTo("high");
        assertThat(saved.get(0).summary()).isEqualTo("Useful summary.");
        assertThat(saved.get(0).detail()).isEqualTo("Useful summary.\n\nImplementation detail.");
        assertThat(saved.get(1).summary()).isEqualTo("Clean B");
    }

    @Test
    void createBatchStampsSpecialAgentOriginsOnce()
    {
        service.createBatch("thread-1", List.of(
                new BacklogServiceImpl.NewBacklogItem(
                        "Triage issue", "body", List.of("issue", "remote-intake"), null),
                new BacklogServiceImpl.NewBacklogItem(
                        "Legacy triage", "body", List.of("issue", "bytequay-intake"), null),
                new BacklogServiceImpl.NewBacklogItem(
                        "Review hotspot", "body", List.of("quality-scan"), null)));

        ArgumentCaptor<BacklogItem> captor = ArgumentCaptor.forClass(BacklogItem.class);
        verify(store, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(BacklogItem::origin)
                .containsExactly("issue-monitor", "issue-monitor", "quality-scan");
    }

    @Test
    void editingTagsDoesNotRewriteOrigin()
    {
        BacklogItem monitored = BacklogItem.create(
                "b1", "thread-1", "ws-1", "Title", "Body",
                List.of("issue", "bytequay-intake"), BacklogItem.PRIORITY_MEDIUM,
                BacklogItem.SOURCE_AGENT, BacklogItem.CREATED_BY_TRUNK_AGENT, NOW, List.of());
        when(store.findById("b1")).thenReturn(Optional.of(monitored));

        BacklogItem updated = service.update("b1", null, null, List.of("edited"), null);

        assertThat(updated.tags()).containsExactly("edited");
        assertThat(updated.origin()).isEqualTo("issue-monitor");
    }

    @Test
    void createBatchRejectsAnEmptyList()
    {
        assertThatThrownBy(() -> service.createBatch("thread-1", List.of()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void skipMovesToDiscardedWithReason()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));

        BacklogItem skipped = service.skip("b1", "out of scope");

        assertThat(skipped.status()).isEqualTo("discarded");
        assertThat(skipped.rejectionReason()).isEqualTo("out of scope");
        assertThat(skipped.rejectedAt()).isNotNull();
        // The decision is recorded for the distillation log.
        verify(distillation).record(
                eq("backlog-skip"), eq("b1"), eq("skipped"), eq("out of scope"), any(), any(), any());
    }

    @Test
    void skipOnAResolvedItemIs409()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", true, "task-1")));
        assertThatThrownBy(() -> service.skip("b1", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolveLinksTheItemToTheTaskItSpawned()
    {
        Task task = task("thread-1", TaskPhase.IMPLEMENTING, null, null);
        BacklogItem inProgress = item("b1", false, null).markInProgress(NOW);
        BacklogItem persisted = inProgress.markResolved("task-42", NOW);
        when(store.findById("b1")).thenReturn(
                Optional.of(inProgress), Optional.of(persisted));
        when(taskStore.findTaskById("task-42")).thenReturn(Optional.of(task));
        when(store.resolveIfInProgressAndUnlinked(eq("b1"), eq("task-42"), any()))
                .thenReturn(true);

        BacklogItem resolved = service.resolve("b1", "task-42");

        assertThat(resolved.status()).isEqualTo("resolved");
        assertThat(resolved.linkedTaskId()).isEqualTo("task-42");
        verify(store).resolveIfInProgressAndUnlinked(eq("b1"), eq("task-42"), any());
        verify(distillation).record(
                eq("backlog-resolve"), eq("b1"), eq("resolved"), eq(null), any(), any(), any());
    }

    @Test
    void resolveOnAnAlreadyResolvedItemIs409()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", true, "task-1")));
        assertThatThrownBy(() -> service.resolve("b1", "task-2"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolveOnAnUnknownItemIs404()
    {
        when(store.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve("missing", "task-1"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolveRejectsAnOpenItemBeforeLinkingTheTask()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));

        assertThatThrownBy(() -> service.resolve("b1", "task-42"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not in progress");

        verify(taskStore, never()).findTaskById(any());
        verify(store, never()).resolveIfInProgressAndUnlinked(any(), any(), any());
    }

    @Test
    void resolveRejectsAnInProgressItemThatAlreadyHasATask()
    {
        BacklogItem linked = item("b1", false, null)
                .markInProgress(NOW)
                .markResolved("task-1", NOW)
                .markCreated()
                .markInProgress(NOW);
        when(store.findById("b1")).thenReturn(Optional.of(linked));

        assertThatThrownBy(() -> service.resolve("b1", "task-42"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already linked");

        verify(taskStore, never()).findTaskById(any());
        verify(store, never()).resolveIfInProgressAndUnlinked(any(), any(), any());
    }

    @Test
    void resolveRejectsAnUnknownTask()
    {
        when(store.findById("b1")).thenReturn(
                Optional.of(item("b1", false, null).markInProgress(NOW)));
        when(taskStore.findTaskById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("b1", "missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("task not found");

        verify(store, never()).resolveIfInProgressAndUnlinked(any(), any(), any());
    }

    @Test
    void resolveRejectsATaskFromAnotherTrunk()
    {
        Task task = task("thread-2", TaskPhase.IMPLEMENTING, null, null);
        when(store.findById("b1")).thenReturn(
                Optional.of(item("b1", false, null).markInProgress(NOW)));
        when(taskStore.findTaskById("task-42")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.resolve("b1", "task-42"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different trunks");

        verify(store, never()).resolveIfInProgressAndUnlinked(any(), any(), any());
    }

    @Test
    void resolveRejectsATaskAlreadyLinkedFromAnotherBacklogItem()
    {
        BacklogItem item = item("b1", false, null).markInProgress(NOW);
        BacklogItem existing = item("b2", true, "task-42");
        Task task = task("thread-1", TaskPhase.IMPLEMENTING, null, null);
        when(store.findById("b1")).thenReturn(Optional.of(item));
        when(taskStore.findTaskById("task-42")).thenReturn(Optional.of(task));
        when(store.findByThread("thread-1")).thenReturn(List.of(item, existing));

        assertThatThrownBy(() -> service.resolve("b1", "task-42"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("another backlog item");

        verify(store, never()).resolveIfInProgressAndUnlinked(any(), any(), any());
    }

    @Test
    void resolveRejectsAConcurrentChangeWithoutRecordingAResolution()
    {
        BacklogItem item = item("b1", false, null).markInProgress(NOW);
        Task task = task("thread-1", TaskPhase.IMPLEMENTING, null, null);
        when(store.findById("b1")).thenReturn(Optional.of(item));
        when(taskStore.findTaskById("task-42")).thenReturn(Optional.of(task));
        when(store.resolveIfInProgressAndUnlinked(eq("b1"), eq("task-42"), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.resolve("b1", "task-42"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed while");

        verify(distillation, never()).record(
                eq("backlog-resolve"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolveTurnsAUniqueTaskLinkRaceIntoAConflict()
    {
        BacklogItem item = item("b1", false, null).markInProgress(NOW);
        Task task = task("thread-1", TaskPhase.IMPLEMENTING, null, null);
        when(store.findById("b1")).thenReturn(Optional.of(item));
        when(taskStore.findTaskById("task-42")).thenReturn(Optional.of(task));
        when(store.resolveIfInProgressAndUnlinked(eq("b1"), eq("task-42"), any()))
                .thenThrow(new UncategorizedSQLException(
                        "resolve backlog", "UPDATE backlog_item",
                        new SQLException(
                                "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed "
                                        + "(UNIQUE constraint failed: backlog_item.linked_task_id)",
                                null,
                                19)));

        assertThatThrownBy(() -> service.resolve("b1", "task-42"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("another backlog item");

        verify(distillation, never()).record(
                eq("backlog-resolve"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listShipsAResolvedItemWhoseTaskMergedAndCompleted()
    {
        Task merged = task(TaskPhase.COMPLETED, 44, "merged");
        when(store.findByThread("thread-1")).thenReturn(List.of(item("b1", true, "task-42")));
        when(taskStore.findTaskById("task-42")).thenReturn(Optional.of(merged));

        List<BacklogItem> listed = service.list("thread-1");

        assertThat(listed).singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo("shipped"));
        verify(store).save(any());
        verify(distillation).record(
                eq("backlog-ship"), eq("b1"), eq("shipped"), eq(null), any(), any(), any());
    }

    @Test
    void listClosesAResolvedItemWhoseTaskCompletedWithoutMerging()
    {
        Task closed = task(TaskPhase.COMPLETED, 44, "closed");
        when(store.findByThread("thread-1")).thenReturn(List.of(item("b1", true, "task-42")));
        when(taskStore.findTaskById("task-42")).thenReturn(Optional.of(closed));

        List<BacklogItem> listed = service.list("thread-1");

        assertThat(listed).singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo("closed"));
        verify(distillation).record(
                eq("backlog-close"), eq("b1"), eq("closed"), eq(null), any(), any(), any());
    }

    @Test
    void listLeavesAResolvedItemAloneWhileItsTaskIsStillInFlight()
    {
        // The cut task hasn't reached COMPLETED yet — the item stays "resolved"
        // (shown as "Task cut" in the panel), and nothing is written.
        Task inFlight = task(TaskPhase.IMPLEMENTING, 44, "open");
        when(store.findByThread("thread-1")).thenReturn(List.of(item("b1", true, "task-42")));
        when(taskStore.findTaskById("task-42")).thenReturn(Optional.of(inFlight));

        List<BacklogItem> listed = service.list("thread-1");

        assertThat(listed).singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo("resolved"));
        verify(store, never()).save(any());
    }

    private static Task task(TaskPhase phase, Integer prNumber, String prState)
    {
        return task("thread-1", phase, prNumber, prState);
    }

    private static Task task(
            String threadId, TaskPhase phase, Integer prNumber, String prState)
    {
        Task task = mock(Task.class);
        when(task.threadId()).thenReturn(threadId);
        when(task.phase()).thenReturn(phase);
        when(task.prNumber()).thenReturn(prNumber);
        when(task.prState()).thenReturn(prState);
        return task;
    }

    @Test
    void reviveRestoresADiscardedItemToOpen()
    {
        when(store.findById("b1")).thenReturn(
                Optional.of(item("b1", false, null).markNotToProceed("nope", NOW)));

        BacklogItem revived = service.revive("b1");

        assertThat(revived.status()).isEqualTo("open");
        assertThat(revived.rejectionReason()).isNull();
        assertThat(revived.rejectedAt()).isNull();
    }

    @Test
    void reviveOnANonRejectedItemIs409()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));
        assertThatThrownBy(() -> service.revive("b1"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listForWorkspaceAppliesEveryFilter()
    {
        BacklogItem a = BacklogItem.create("a", "t1", "ws", "Add meter", "ui work", List.of("ui"),
                "medium", "manual", "user", NOW, List.of());
        BacklogItem b = BacklogItem.create("b", "t2", "ws", "Fix parser", "backend bits", List.of("backend"),
                "high", "manual", "user", NOW, List.of()).markNotToProceed(null, NOW);
        when(store.findByWorkspace("ws")).thenReturn(List.of(a, b));

        assertThat(service.listForWorkspace("ws", null, null, null, null)).containsExactly(a, b);
        assertThat(service.listForWorkspace("ws", "open", null, null, null)).containsExactly(a);
        assertThat(service.listForWorkspace("ws", null, "t2", null, null)).containsExactly(b);
        assertThat(service.listForWorkspace("ws", null, null, "ui", null)).containsExactly(a);
        assertThat(service.listForWorkspace("ws", null, null, null, "parser")).containsExactly(b);
    }

    private static BacklogItem item(String id, boolean started, String linkedTaskId)
    {
        BacklogItem base = BacklogItem.create(
                id, "thread-1", "ws-1", "Title", "Body", List.of("ui"),
                BacklogItem.PRIORITY_MEDIUM, BacklogItem.SOURCE_MANUAL,
                BacklogItem.CREATED_BY_USER, NOW, List.of());
        return started ? base.markResolved(linkedTaskId, NOW) : base;
    }
}
