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

import java.util.Locale;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** An imported PR's durable conversation and its initial context, independent of cached PR retention. */
public record PullRequestTask(String taskId, String workspaceId, String repo, int number, String initialContext)
{
    public PullRequestTask
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(initialContext, "initialContext is null");
        checkArgument(!taskId.isBlank() && !workspaceId.isBlank() && !initialContext.isBlank(), "task context must not be blank");
        checkArgument(repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") && number > 0, "invalid pull request");
        repo = repo.toLowerCase(Locale.ROOT);
    }
}
