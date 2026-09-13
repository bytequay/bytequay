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
package com.bytequay.app.localreview;

import com.bytequay.core.localreview.LocalReviewLearningAnchor;
import com.bytequay.core.localreview.LocalReviewMessage.Author;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Display projection; input keys and mutation replay records never reach the model or renderer. */
public record LocalReviewView(String id, String taskId, String workspaceId, String repo, int number,
        String path, String side, int startLine, int line, Optional<String> baseSha, Optional<String> headSha, String code,
        boolean resolved, long version, Instant createdAt, Instant updatedAt, long taskVersion, List<Message> messages,
        Optional<LocalReviewLearningAnchor> learningAnchor)
{
    public LocalReviewView(String id, String taskId, String workspaceId, String repo, int number,
            String path, String side, int startLine, int line, Optional<String> baseSha, Optional<String> headSha, String code,
            boolean resolved, long version, Instant createdAt, Instant updatedAt, long taskVersion, List<Message> messages)
    {
        this(id, taskId, workspaceId, repo, number, path, side, startLine, line, baseSha, headSha, code,
                resolved, version, createdAt, updatedAt, taskVersion, messages, Optional.empty());
    }

    public LocalReviewView
    {
        messages = ImmutableList.copyOf(messages);
    }

    public record Message(String id, Author author, String body, Instant createdAt, Optional<String> turnId,
            Optional<String> queuedInputId, Optional<String> messageEventId, Optional<String> deliveryState, Optional<String> deliveryError) {}
}
