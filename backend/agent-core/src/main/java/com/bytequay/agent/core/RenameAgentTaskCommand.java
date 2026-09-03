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
package com.bytequay.agent.core;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A person's choice of title for a task, guarded by the task version they were looking at.
 */
public record RenameAgentTaskCommand(
        TaskId taskId,
        long expectedVersion,
        String title)
{
    /** Generous: a header truncates what it cannot show, so the bound only stops nonsense. */
    public static final int MAX_LENGTH = 200;

    public RenameAgentTaskCommand
    {
        requireNonNull(taskId, "taskId is null");
        checkArgument(expectedVersion >= 0, "expectedVersion is negative");
        checkNotBlank(title, "title");
        checkArgument(title.strip().length() <= MAX_LENGTH, "title is longer than %s characters", MAX_LENGTH);
    }
}
