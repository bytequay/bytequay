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

/** Asking about one comment of a pull request the task owns; a blank question asks for the summary. */
public record AskCommentCommand(String workspaceId, String taskId, String repo, int number, String key, String question)
{
    public AskCommentCommand
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(key, "key is null");
        requireNonNull(question, "question is null");
        checkArgument(number > 0, "Invalid pull request number");
        checkArgument(key.matches("(review|review_comment|issue_comment):[0-9]{1,19}"), "Invalid comment key");
        checkArgument(question.length() <= 16_000, "Question must contain at most 16000 characters");
    }
}
