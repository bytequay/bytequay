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
package com.bytequay.core.discussion;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One question or one answer in a discussion.
 *
 * @param error present only on a question whose answer failed, carrying the reader-facing
 *         reason; an answer never has one
 */
public record DiscussionMessage(String id, Author author, String body, Instant createdAt, Optional<String> error)
{
    public enum Author
    {
        USER,
        AGENT
    }

    public DiscussionMessage
    {
        requireNonNull(id, "id is null");
        requireNonNull(author, "author is null");
        requireNonNull(body, "body is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(error, "error is null");
        checkArgument(id.matches("[A-Za-z0-9._:-]{1,100}"), "Invalid message id");
        checkArgument(!body.isBlank() && body.length() <= 16_000, "Message must contain 1-16000 characters");
        checkArgument(error.isEmpty() || author == Author.USER, "Only a question carries a failure");
    }

    /** Records why this question's answer failed, so the reader can retry it. */
    public DiscussionMessage failed(String reason)
    {
        return new DiscussionMessage(id, author, body, createdAt, Optional.of(reason));
    }
}
