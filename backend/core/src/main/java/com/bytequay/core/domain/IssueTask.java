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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A durable issue-to-development-task link with its accepted initial context. */
public record IssueTask(String taskId, String workspaceId, GitHubIssueSource source, Optional<String> instructions, Instant createdAt)
{
    public IssueTask
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(source, "source is null");
        requireNonNull(instructions, "instructions is null");
        requireNonNull(createdAt, "createdAt is null");
        checkArgument(instructions.stream().noneMatch(String::isBlank), "instructions must not be blank");
        checkArgument(!taskId.isBlank() && !workspaceId.isBlank(), "task context must not be blank");
    }
}
