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
package com.bytequay.core.review;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

/**
 * The publication reservation and result of one draft item. A reservation is taken atomically
 * before the send so no other route can send the item; a known result records the remote ids
 * ByteQuay could establish, and an uncertain one waits for reconciliation rather than a resend.
 */
public record ReviewPublication(String attemptId, State state, OptionalLong remoteReviewId, OptionalLong remoteCommentId, Optional<String> url, Instant at)
{
    public ReviewPublication
    {
        requireNonNull(attemptId, "attemptId is null");
        requireNonNull(state, "state is null");
        requireNonNull(remoteReviewId, "remoteReviewId is null");
        requireNonNull(remoteCommentId, "remoteCommentId is null");
        requireNonNull(url, "url is null");
        requireNonNull(at, "at is null");
    }

    public enum State
    {
        RESERVED,
        PUBLISHED,
        UNCERTAIN
    }
}
