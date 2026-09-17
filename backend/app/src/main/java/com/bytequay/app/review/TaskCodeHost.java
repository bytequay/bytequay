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
package com.bytequay.app.review;

import static java.util.Objects.requireNonNull;

/** A development task's working copy opened from one of its pull requests; every request re-checks that the task still owns it. */
public record TaskCodeHost(String workspaceId, String taskId, String repo, int number)
{
    public TaskCodeHost
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(repo, "repo is null");
    }
}
