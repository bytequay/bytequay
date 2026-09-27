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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A workspace's local grouping of pull requests, issues, Jira tickets and tasks toward one outcome.
 * A goal never runs, and changing one never writes to GitHub or Jira. One goal per workspace is
 * pinned: the one its pages follow.
 */
public record Goal(
        String id,
        String workspaceId,
        String title,
        String description,
        boolean pinned,
        Optional<GoalSource> source,
        List<GoalItem> items,
        Instant createdAt,
        Instant updatedAt)
{
    public Goal
    {
        requireNonNull(id, "id is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(title, "title is null");
        requireNonNull(description, "description is null");
        requireNonNull(source, "source is null");
        items = ImmutableList.copyOf(requireNonNull(items, "items is null"));
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(!id.isBlank(), "id is blank");
        checkArgument(!workspaceId.isBlank(), "workspaceId is blank");
        checkArgument(!title.isBlank(), "title is blank");
        Set<String> seen = new HashSet<>();
        for (GoalItem item : items) {
            checkArgument(seen.add(item.kind() + " " + item.key()), "goal lists %s %s twice", item.kind(), item.key());
        }
    }

    public boolean contains(GoalItemKind kind, String key)
    {
        return items.stream().anyMatch(item -> item.is(kind, key));
    }

    public Goal withItems(List<GoalItem> replacement, Instant at)
    {
        return new Goal(id, workspaceId, title, description, pinned, source, replacement, createdAt, at);
    }

    public Goal withPinned(boolean value, Instant at)
    {
        return new Goal(id, workspaceId, title, description, value, source, items, createdAt, at);
    }

    public Goal withText(String newTitle, String newDescription, Instant at)
    {
        return new Goal(id, workspaceId, newTitle, newDescription, pinned, source, items, createdAt, at);
    }
}
