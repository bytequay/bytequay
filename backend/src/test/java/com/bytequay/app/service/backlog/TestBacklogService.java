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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.BacklogStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.distillation.DistillationSignalService;
import com.bytequay.app.service.threads.ThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private DistillationSignalService distillation;
    private BacklogServiceImpl service;

    @BeforeEach
    void setUp()
    {
        store = mock(BacklogStore.class);
        threadService = mock(ThreadService.class);
        threadStore = mock(ThreadStore.class);
        distillation = mock(DistillationSignalService.class);
        service = new BacklogServiceImpl(store, threadService, threadStore, distillation);
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createPersistsAFreshItem()
    {
        Thread trunk = mock(Thread.class);
        when(trunk.workspaceId()).thenReturn("ws-1");
        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(trunk));

        BacklogItem saved = service.create("thread-1", "Add a cost meter", "body text", List.of("ui"), null);

        ArgumentCaptor<BacklogItem> captor = ArgumentCaptor.forClass(BacklogItem.class);
        verify(store).save(captor.capture());
        BacklogItem persisted = captor.getValue();
        assertThat(persisted.threadId()).isEqualTo("thread-1");
        assertThat(persisted.title()).isEqualTo("Add a cost meter");
        assertThat(persisted.body()).isEqualTo("body text");
        assertThat(persisted.tags()).containsExactly("ui");
        assertThat(persisted.startedAt()).isNull();
        assertThat(persisted.linkedTaskId()).isNull();
        assertThat(persisted.id()).isNotBlank();
        // A manual create lands at the head of the lifecycle with the default
        // priority when none was supplied.
        assertThat(persisted.status()).isEqualTo("open");
        assertThat(persisted.source()).isEqualTo("manual");
        assertThat(persisted.createdBy()).isEqualTo("user");
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

        BacklogService.StartResult result = service.startDevelopment("b1");

        // The item content is posted into the trunk as a planning prompt; no
        // task is cut here. The prompt carries the backlog id plus the
        // confidence-or-confirm contract the trunk must follow before cutting.
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(threadService).sendTrunk(eq("thread-1"), prompt.capture());
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
        List<BacklogService.NewBacklogItem> inputs = List.of(
                new BacklogService.NewBacklogItem("Clean A", "body a", List.of("ui"), "high"),
                new BacklogService.NewBacklogItem("Clean B", "body b", null, null));

        BacklogService.BatchResult result = service.createBatch("thread-1", inputs);

        assertThat(result.backlogItemIds()).hasSize(2);
        assertThat(result.relatedBacklogGroupId()).isNotBlank();

        ArgumentCaptor<BacklogItem> captor = ArgumentCaptor.forClass(BacklogItem.class);
        verify(store, times(2)).save(captor.capture());
        List<BacklogItem> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(it -> {
            assertThat(it.source()).isEqualTo("agent");
            assertThat(it.createdBy()).isEqualTo("trunk-agent");
            assertThat(it.status()).isEqualTo("open");
        });
        // Each item references its sibling; priority is carried through.
        assertThat(saved.get(0).relatedBacklogIds()).containsExactly(saved.get(1).id());
        assertThat(saved.get(1).relatedBacklogIds()).containsExactly(saved.get(0).id());
        assertThat(saved.get(0).priority()).isEqualTo("high");
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
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));

        BacklogItem resolved = service.resolve("b1", "task-42");

        assertThat(resolved.status()).isEqualTo("resolved");
        assertThat(resolved.linkedTaskId()).isEqualTo("task-42");
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
