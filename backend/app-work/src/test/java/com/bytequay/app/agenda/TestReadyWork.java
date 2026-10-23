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
package com.bytequay.app.agenda;

import com.bytequay.app.workitem.WorkItemView;
import com.bytequay.core.domain.WorkItem;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

class TestReadyWork
{
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void putsReadyWorkFirstByPriorityThenByTheWorkWaitingOnItThenByPlace()
    {
        List<WorkItemView> items = ImmutableList.of(
                work("a", 0, WorkItem.State.PLANNED, WorkItem.Priority.NORMAL),
                work("b", 1, WorkItem.State.PLANNED, WorkItem.Priority.NORMAL),
                work("c", 2, WorkItem.State.PLANNED, WorkItem.Priority.HIGH),
                work("d", 3, WorkItem.State.PLANNED, WorkItem.Priority.NORMAL, "b"),
                work("e", 4, WorkItem.State.PLANNED, WorkItem.Priority.LOW),
                work("f", 5, WorkItem.State.WORKING, WorkItem.Priority.NORMAL),
                work("g", 6, WorkItem.State.DONE, WorkItem.Priority.NORMAL));

        assertThat(ids(ReadyWork.ready(items))).containsExactly("c", "b", "a", "e");
        assertThat(ReadyWork.waiting(items.get(3), items)).isTrue();
        assertThat(ReadyWork.unblocks(items.get(1), items)).isEqualTo(1);

        List<WorkItemView> finished = items.stream().map(item -> item.id().equals("b") ? work("b", 1, WorkItem.State.DONE, WorkItem.Priority.NORMAL) : item)
                .collect(toImmutableList());
        assertThat(ids(ReadyWork.ready(finished))).containsExactly("c", "a", "d", "e");
    }

    private static List<String> ids(List<WorkItemView> items)
    {
        return items.stream().map(WorkItemView::id).collect(toImmutableList());
    }

    private static WorkItemView work(String id, long place, WorkItem.State state, WorkItem.Priority priority, String... blockedBy)
    {
        return new WorkItemView(id, "ws1", id, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY, state, 1, ImmutableList.of(),
                ImmutableList.of(), CREATED.plusSeconds(place), CREATED.plusSeconds(place), priority, ImmutableList.copyOf(blockedBy), Optional.of("epic"), place, false,
                ImmutableList.of());
    }
}
