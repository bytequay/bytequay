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
package com.bytequay.app.domain;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Read-only provider trace for one physically typed Trunk request.
 *
 * <p>This is deliberately not a {@link ThreadMessage}: provider logs do not
 * participate in conversation ordering or cursors. {@code id} is the stable
 * tuple {@code (executionId, logSeq, eventIndex)} encoded for React keys and
 * reload deduplication.
 */
public record TrunkTraceEvent(
        String id,
        String trunkId,
        String turnId,
        String requestMessageId,
        String executionId,
        long logSeq,
        int eventIndex,
        String type,
        String contentJson,
        Instant ts)
{
    public TrunkTraceEvent
    {
        requireText(id, "id");
        requireText(trunkId, "trunkId");
        requireText(turnId, "turnId");
        requireText(requestMessageId, "requestMessageId");
        requireText(executionId, "executionId");
        requireText(type, "type");
        requireNonNull(contentJson, "contentJson is null");
        requireNonNull(ts, "ts is null");
        if (logSeq < 0) {
            throw new IllegalArgumentException("logSeq is negative");
        }
        if (eventIndex < 0) {
            throw new IllegalArgumentException("eventIndex is negative");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
