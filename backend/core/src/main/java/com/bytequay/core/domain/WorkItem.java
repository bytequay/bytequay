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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * One accepted outcome, independent of its source, execution attempts and Goal or Epic membership.
 *
 * @param rank its place in its Goal's order, lowest first; equal places keep creation order
 * @param blockedBy the work of the same Goal or Epic it waits on
 * @param epicId the canonical identity of the issue or ticket marked as the Epic the work belongs to
 * @param epicRank its place in its Epic's order, lowest first; equal places keep creation order
 */
public record WorkItem(
        String id,
        String workspaceId,
        Optional<String> goalId,
        String title,
        Kind kind,
        String acceptanceCriteria,
        CompletionRule completionRule,
        State state,
        long revision,
        List<WorkItemLink> links,
        List<WorkItemEvent> history,
        Instant createdAt,
        Instant updatedAt,
        Priority priority,
        long rank,
        List<String> blockedBy,
        Optional<String> epicId,
        long epicRank)
{
    public WorkItem
    {
        checkText(id, "id");
        checkText(workspaceId, "workspaceId");
        requireNonNull(goalId, "goalId is null");
        goalId.ifPresent(value -> checkText(value, "goalId"));
        checkText(title, "title", 500);
        requireNonNull(kind, "kind is null");
        requireNonNull(acceptanceCriteria, "acceptanceCriteria is null");
        checkArgument(acceptanceCriteria.length() <= 65536, "acceptance criteria are too long");
        requireNonNull(completionRule, "completionRule is null");
        requireNonNull(state, "state is null");
        checkArgument(revision >= 0, "revision is negative");
        links = ImmutableList.copyOf(links);
        history = ImmutableList.copyOf(history);
        checkArgument(links.stream().map(WorkItemLink::identity).distinct().count() == links.size(), "duplicate work item link");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        // Work saved before priorities and blockers existed reads as Normal and waiting on nothing.
        priority = requireNonNullElse(priority, Priority.NORMAL);
        blockedBy = blockedBy == null ? ImmutableList.of() : ImmutableList.copyOf(blockedBy);
        checkArgument(!blockedBy.contains(id), "work cannot wait on itself");
        checkArgument(blockedBy.stream().distinct().count() == blockedBy.size(), "duplicate blocker");
        // Work saved before Epics held work belongs to none.
        epicId = requireNonNullElse(epicId, Optional.empty());
        epicId.ifPresent(value -> checkText(value, "epicId"));
    }

    public enum Kind { LEARN, INVESTIGATE, DEVELOP }

    public enum State { PLANNED, WORKING, DONE, DROPPED }

    public enum CompletionRule { MERGED_DELIVERY, ACCEPTED_RESULT }

    public enum Priority { HIGH, NORMAL, LOW }
}
