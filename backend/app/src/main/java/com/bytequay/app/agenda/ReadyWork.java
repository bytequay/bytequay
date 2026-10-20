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

import java.util.Comparator;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * Which planned work can start, in the order the Goal panel's Next view shows it: the user's priority first, then the open
 * work waiting on it, then its place in the Epic. Keep the two in step; goals/model.ts holds the panel's copy.
 */
final class ReadyWork
{
    private ReadyWork() {}

    /** The planned work of one Epic that waits on nothing, in start order. */
    static List<WorkItemView> ready(List<WorkItemView> epic)
    {
        return epic.stream()
                .filter(item -> item.state() == WorkItem.State.PLANNED && !waiting(item, epic))
                .sorted(Comparator.comparingInt((WorkItemView item) -> item.priority().ordinal())
                        .thenComparing(Comparator.comparingInt((WorkItemView item) -> unblocks(item, epic)).reversed())
                        .thenComparingLong(WorkItemView::epicRank)
                        .thenComparing(WorkItemView::createdAt))
                .collect(toImmutableList());
    }

    /** Whether open work waits on other open work of its Epic, or on a source that reports itself blocked. */
    static boolean waiting(WorkItemView item, List<WorkItemView> epic)
    {
        return epic.stream().anyMatch(other -> item.blockedBy().contains(other.id()) && open(other)) || item.links().stream().anyMatch(link -> link.blocked());
    }

    /** How much open work of the Epic waits on this item. */
    static int unblocks(WorkItemView item, List<WorkItemView> epic)
    {
        return (int) epic.stream().filter(other -> open(other) && other.blockedBy().contains(item.id())).count();
    }

    static boolean open(WorkItemView item)
    {
        return item.state() == WorkItem.State.PLANNED || item.state() == WorkItem.State.WORKING;
    }
}
