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
package com.bytequay.core.localreview;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A durable private message; input routing is frozen before dispatch so uncertain retries cannot duplicate work. */
public record LocalReviewMessage(String id, Author author, String body, Instant createdAt,
        Optional<String> inputKey, Optional<Routing> routing, Optional<String> turnId, Optional<String> deliveryError)
{
    public LocalReviewMessage
    {
        requireNonNull(id, "id is null");
        requireNonNull(author, "author is null");
        requireNonNull(body, "body is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(inputKey, "inputKey is null");
        requireNonNull(routing, "routing is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(deliveryError, "deliveryError is null");
        checkArgument(id.matches("[A-Za-z0-9._:-]{1,100}"), "Invalid message id");
        checkArgument(!body.isBlank() && body.length() <= 16_000, "Message must contain 1–16000 characters");
        checkArgument((author == Author.USER) == inputKey.isPresent() && inputKey.isPresent() == routing.isPresent(), "Invalid message routing");
    }

    public LocalReviewMessage withError(Optional<String> error)
    {
        return new LocalReviewMessage(id, author, body, createdAt, inputKey, routing, turnId, error);
    }

    public LocalReviewMessage withRouting(Routing value)
    {
        return new LocalReviewMessage(id, author, body, createdAt, inputKey, Optional.of(value), turnId, deliveryError);
    }

    public enum Author
    {
        USER,
        AGENT
    }

    public enum Routing
    {
        SEND,
        QUEUE
    }
}
