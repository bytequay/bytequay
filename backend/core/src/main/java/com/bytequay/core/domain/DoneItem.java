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

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Something the viewer finished: a pull request merged or reviewed, an issue closed, or a task or work item done.
 *
 * @param workspaceId the watched workspace the item belongs to; absent for a repository nobody watches
 * @param id the task or work item id
 */
public record DoneItem(Kind kind, String title, Optional<String> repo, Optional<Integer> number, Optional<String> workspaceId, Optional<String> id, Instant at)
{
    public enum Kind
    {
        MERGED, MERGED_BY_YOU, APPROVED, CHANGES_REQUESTED, REVIEWED, ISSUE_CLOSED, TASK_DONE, WORK_DONE
    }

    public DoneItem
    {
        requireNonNull(kind, "kind is null");
        checkText(title, "title");
        checkOptionalText(repo, "repo");
        requireNonNull(number, "number is null");
        checkOptionalText(workspaceId, "workspaceId");
        checkOptionalText(id, "id");
        requireNonNull(at, "at is null");
        checkArgument(repo.isPresent() == number.isPresent(), "A repository item needs both its repository and number");
    }

    public static DoneItem github(Kind kind, String title, String repo, int number, Instant at)
    {
        return new DoneItem(kind, title, Optional.of(repo), Optional.of(number), Optional.empty(), Optional.empty(), at);
    }

    public static DoneItem local(Kind kind, String title, String workspaceId, String id, Instant at)
    {
        return new DoneItem(kind, title, Optional.empty(), Optional.empty(), Optional.of(workspaceId), Optional.of(id), at);
    }

    public DoneItem inWorkspace(Optional<String> workspace)
    {
        return new DoneItem(kind, title, repo, number, workspace, id, at);
    }
}
