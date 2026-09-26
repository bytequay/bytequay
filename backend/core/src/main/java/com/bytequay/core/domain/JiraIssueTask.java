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

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** One durable Jira source association with the exact accepted user instruction. */
public record JiraIssueTask(String taskId, String workspaceId, JiraIssueSource source, String instructions, Instant createdAt)
{
    public JiraIssueTask
    {
        checkText(taskId, "taskId");
        checkText(workspaceId, "workspaceId");
        requireNonNull(source, "source is null");
        checkText(instructions, "instructions");
        requireNonNull(createdAt, "createdAt is null");
    }
}
