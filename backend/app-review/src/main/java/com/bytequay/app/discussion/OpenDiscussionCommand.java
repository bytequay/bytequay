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
package com.bytequay.app.discussion;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Opening a discussion on a quoted passage.
 *
 * @param occurrence which appearance of the quote in that answer the reader highlighted, 1-based;
 *         refused below 1, so a body omitting the field cannot silently mean the first
 */
public record OpenDiscussionCommand(String workspaceId, String taskId, String turnId, String quote, int occurrence, String question)
{
    public OpenDiscussionCommand
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(quote, "quote is null");
        requireNonNull(question, "question is null");
        checkArgument(occurrence >= 1, "Invalid quote occurrence");
        checkArgument(!question.isBlank() && question.length() <= 16_000, "Question must contain 1-16000 characters");
    }
}
