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

import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.domain.QueuedTaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestTaskQueueService
{
    private static final String THREAD_ID = "t1";

    private List<QueuedTask> queueState;
    private TaskQueueService service;

    @BeforeEach
    void setUp()
    {
        queueState = new ArrayList<>();
        ThreadStore store = mock(ThreadStore.class);
        when(store.findThreadById(eq(THREAD_ID)))
                .thenAnswer(inv -> Optional.of(threadWith(queueState)));
        doAnswer(inv -> {
            queueState = new ArrayList<>(inv.getArgument(1));
            return null;
        }).when(store).updateThreadQueue(eq(THREAD_ID), any());
        service = new TaskQueueService(store);
    }

    @Test
    void appendAssignsIncrementingPositionsFromOne()
    {
        QueuedTask first = service.append(THREAD_ID, "first", BranchBase.MAIN, null);
        QueuedTask second = service.append(THREAD_ID, "second", BranchBase.STACKED_ON_PREVIOUS, "go");

        assertThat(first.position()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(QueuedTaskStatus.PENDING);
        assertThat(second.position()).isEqualTo(2);
        assertThat(second.branchBase()).isEqualTo(BranchBase.STACKED_ON_PREVIOUS);
        assertThat(second.initialPrompt()).isEqualTo("go");
        assertThat(queueState).hasSize(2);
    }

    @Test
    void appendRejectsBlankAndOverlongTitles()
    {
        assertThatThrownBy(() -> service.append(THREAD_ID, "  ", BranchBase.MAIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title is required");
        String tooLong = "x".repeat(TaskQueueService.MAX_TITLE_CHARS + 1);
        assertThatThrownBy(() -> service.append(THREAD_ID, tooLong, BranchBase.MAIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void reorderPermutesPendingPositionsKeepingTheSlots()
    {
        service.append(THREAD_ID, "a", BranchBase.MAIN, null); // pos 1
        service.append(THREAD_ID, "b", BranchBase.MAIN, null); // pos 2
        service.append(THREAD_ID, "c", BranchBase.MAIN, null); // pos 3

        service.reorder(THREAD_ID, List.of(3, 1, 2));

        // Slots 1,2,3 are preserved; the entry formerly at pos 3 ('c')
        // now sits in slot 1, 'a' in slot 2, 'b' in slot 3.
        assertThat(titleAt(1)).isEqualTo("c");
        assertThat(titleAt(2)).isEqualTo("a");
        assertThat(titleAt(3)).isEqualTo("b");
    }

    @Test
    void reorderRejectsNonPermutations()
    {
        service.append(THREAD_ID, "a", BranchBase.MAIN, null);
        service.append(THREAD_ID, "b", BranchBase.MAIN, null);

        assertThatThrownBy(() -> service.reorder(THREAD_ID, List.of(1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reorder(THREAD_ID, List.of(1, 2, 3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reorder(THREAD_ID, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reorderLeavesMaterializedEntriesPinned()
    {
        service.append(THREAD_ID, "done", BranchBase.MAIN, null);    // pos 1
        service.append(THREAD_ID, "p2", BranchBase.MAIN, null);      // pos 2
        service.append(THREAD_ID, "p3", BranchBase.MAIN, null);      // pos 3
        // Freeze pos 1 as MATERIALIZED.
        queueState.set(0, queueState.get(0).withStatus(QueuedTaskStatus.MATERIALIZED, "t1.k1"));

        // Only positions 2 and 3 are PENDING; swap them.
        service.reorder(THREAD_ID, List.of(3, 2));

        assertThat(statusAt(1)).isEqualTo(QueuedTaskStatus.MATERIALIZED);
        assertThat(titleAt(1)).isEqualTo("done");
        assertThat(titleAt(2)).isEqualTo("p3");
        assertThat(titleAt(3)).isEqualTo("p2");
    }

    @Test
    void dropFlipsPendingToDropped()
    {
        service.append(THREAD_ID, "a", BranchBase.MAIN, null);
        service.append(THREAD_ID, "b", BranchBase.MAIN, null);

        QueuedTask dropped = service.drop(THREAD_ID, 2);

        assertThat(dropped.status()).isEqualTo(QueuedTaskStatus.DROPPED);
        assertThat(statusAt(2)).isEqualTo(QueuedTaskStatus.DROPPED);
        assertThat(statusAt(1)).isEqualTo(QueuedTaskStatus.PENDING);
    }

    @Test
    void dropRejectsMaterializedAndMissing()
    {
        service.append(THREAD_ID, "a", BranchBase.MAIN, null);
        queueState.set(0, queueState.get(0).withStatus(QueuedTaskStatus.MATERIALIZED, "t1.k1"));

        assertThatThrownBy(() -> service.drop(THREAD_ID, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MATERIALIZED");
        assertThatThrownBy(() -> service.drop(THREAD_ID, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no queue entry");
    }

    @Test
    void pendingHeadReturnsLowestPositionPendingEntry()
    {
        service.append(THREAD_ID, "a", BranchBase.MAIN, null);
        service.append(THREAD_ID, "b", BranchBase.MAIN, null);
        queueState.set(0, queueState.get(0).withStatus(QueuedTaskStatus.MATERIALIZED, "t1.k1"));

        Optional<QueuedTask> head = service.pendingHead(threadWith(queueState));
        assertThat(head).isPresent();
        assertThat(head.get().title()).isEqualTo("b");
    }

    private String titleAt(int position)
    {
        return queueState.stream().filter(q -> q.position() == position)
                .findFirst().orElseThrow().title();
    }

    private QueuedTaskStatus statusAt(int position)
    {
        return queueState.stream().filter(q -> q.position() == position)
                .findFirst().orElseThrow().status();
    }

    private static Thread threadWith(List<QueuedTask> queue)
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Thread(
                THREAD_ID, ThreadKind.LOGIC_LOOP, "anthropic", null, "Thread",
                ThreadStatus.IDLE, "claude", 0L, 0L, 0L, now, now, null, null,
                ThreadFlow.BUILD, "ws-default", null, null, null,
                List.copyOf(queue), 1);
    }
}
