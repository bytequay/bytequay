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

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A workspace objective. Work items retain their own identity and history outside this grouping.
 *
 * @param source the issue or Jira ticket whose Goal marking created this goal; kept after unmarking,
 * so marking it again restores this goal rather than starting a second one
 * @param unmarkedAt when that marking was removed while the goal held work
 * @param definition what the user agreed the goal means, accepted from one of the agent's proposals
 * @param revision counts changes to the goal's text and definition; a proposal built from an older revision cannot be accepted
 * @param course the learning path the user accepted, whose lessons are the goal's Learn work items
 */
public record Goal(
        String id,
        String workspaceId,
        String title,
        String description,
        boolean pinned,
        boolean archived,
        Optional<GoalSource> source,
        Optional<Instant> unmarkedAt,
        Optional<GoalDefinition> definition,
        long revision,
        Optional<GoalCourse> course,
        Instant createdAt,
        Instant updatedAt)
{
    public Goal
    {
        checkText(id, "id");
        checkText(workspaceId, "workspaceId");
        checkText(title, "title", 500);
        requireNonNull(description, "description is null");
        requireNonNull(source, "source is null");
        requireNonNull(unmarkedAt, "unmarkedAt is null");
        checkArgument(source.isPresent() || unmarkedAt.isEmpty(), "only a goal created by marking a source can be unmarked");
        requireNonNull(definition, "definition is null");
        checkArgument(revision >= 0, "revision is negative");
        requireNonNull(course, "course is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    /** The source while it is still marked as this goal. */
    public Optional<GoalSource> markedSource()
    {
        return unmarkedAt.isPresent() ? Optional.empty() : source;
    }

    public Goal withPinned(boolean value, Instant at)
    {
        return new Goal(id, workspaceId, title, description, value, archived, source, unmarkedAt, definition, revision, course, createdAt, at);
    }

    public Goal withText(String newTitle, String newDescription, Instant at)
    {
        boolean changed = !newTitle.equals(title) || !newDescription.equals(description);
        return new Goal(id, workspaceId, newTitle, newDescription, pinned, archived, source, unmarkedAt, definition, changed ? revision + 1 : revision, course, createdAt, at);
    }

    /** The same goal under the name its agent gave it; the revision stays, since a name changes nothing the goal means. */
    public Goal named(String name, Instant at)
    {
        return new Goal(id, workspaceId, name, description, pinned, archived, source, unmarkedAt, definition, revision, course, createdAt, at);
    }

    public Goal withMarked(boolean value, Instant at)
    {
        return new Goal(id, workspaceId, title, description, pinned, archived, source, value ? Optional.empty() : Optional.of(at), definition, revision, course, createdAt, at);
    }

    public Goal archive(Instant at)
    {
        return new Goal(id, workspaceId, title, description, false, true, source, unmarkedAt, definition, revision, course, createdAt, at);
    }

    public Goal withCourse(GoalCourse accepted, Instant at)
    {
        return new Goal(id, workspaceId, title, description, pinned, archived, source, unmarkedAt, definition, revision, Optional.of(accepted), createdAt, at);
    }

    public Goal withDefinition(String newTitle, String objective, GoalDefinition accepted, Instant at)
    {
        return new Goal(id, workspaceId, newTitle, objective, pinned, archived, source, unmarkedAt, Optional.of(accepted), revision + 1, course, createdAt, at);
    }
}
