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
package com.bytequay.app.workitem;

import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemContextLink;
import com.bytequay.core.domain.WorkItemEvent;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** One progress card, with its source and attempt activity shown as supporting links. */
public record WorkItemView(
        String id,
        String workspaceId,
        Optional<String> goalId,
        String title,
        WorkItem.Kind kind,
        String acceptanceCriteria,
        WorkItem.CompletionRule completionRule,
        WorkItem.State state,
        long revision,
        List<GoalItemView> links,
        List<WorkItemEvent> history,
        Instant createdAt,
        Instant updatedAt,
        WorkItem.Priority priority,
        long rank,
        List<String> blockedBy,
        Optional<String> epicId,
        long epicRank,
        boolean isEpic,
        List<WorkItemContextLink> contextLinks)
{
    public WorkItemView
    {
        links = ImmutableList.copyOf(links);
        history = ImmutableList.copyOf(history);
        blockedBy = ImmutableList.copyOf(blockedBy);
        contextLinks = contextLinks == null ? ImmutableList.of() : ImmutableList.copyOf(contextLinks);
    }
}
