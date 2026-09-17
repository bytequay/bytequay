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
package com.bytequay.core.context;

import com.google.common.hash.Hashing;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** A frozen reference-to-input binding; provider acceptance is derived from the owning turn, never asserted here. */
public record ContextDelivery(
        String turnId,
        String packetDigest,
        int utf8Bytes,
        int contributionCount,
        String inputDigest,
        int inputUtf8Bytes,
        Optional<String> discussionMessageId,
        Optional<ContextDiscussionSupplement> discussionSupplement,
        Instant preparedAt)
{
    public static final int MAXIMUM_INPUT_BYTES = 256 * 1024;

    public ContextDelivery
    {
        ContextLimits.text(turnId, "context delivery turn", 200);
        requireNonNull(packetDigest, "packetDigest is null");
        requireNonNull(inputDigest, "inputDigest is null");
        checkArgument(packetDigest.matches("sha256:[0-9a-f]{64}"), "Invalid packet digest");
        checkArgument(inputDigest.matches("sha256:[0-9a-f]{64}"), "Invalid input digest");
        checkArgument(utf8Bytes > 0 && utf8Bytes <= ContextPacket.MAXIMUM_BYTES, "Invalid delivered packet size");
        checkArgument(contributionCount >= 0 && contributionCount <= 3, "Invalid delivered contribution count");
        checkArgument(inputUtf8Bytes >= utf8Bytes && inputUtf8Bytes <= MAXIMUM_INPUT_BYTES, "Composed review input exceeds UTF-8 limit");
        requireNonNull(discussionMessageId, "discussionMessageId is null");
        discussionMessageId.ifPresent(id -> ContextLimits.text(id, "discussion message", 200));
        requireNonNull(discussionSupplement, "discussionSupplement is null");
        checkArgument(discussionSupplement.isEmpty() || discussionMessageId.isPresent(), "Only a discussion can have a supplement");
        checkArgument(inputUtf8Bytes >= utf8Bytes + discussionSupplement.map(value -> value.packet().utf8Bytes()).orElse(0), "Input omits discussion supplement bytes");
        requireNonNull(preparedAt, "preparedAt is null");
    }

    /** Hashes the actual composed message; this does not establish that it was submitted or understood. */
    public static String digest(String input)
    {
        return "sha256:" + Hashing.sha256().hashString(input, UTF_8);
    }
}
