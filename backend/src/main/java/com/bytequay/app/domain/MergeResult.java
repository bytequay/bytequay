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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outcome of an attempted pull request merge. Two shapes share the
 * type: an immediate merge (REST {@code PUT .../merge} returned 200,
 * {@code sha} populated, {@code queued=false}) and an enqueue when
 * the target branch has merge queue enabled ({@code sha=null},
 * {@code merged=false}, {@code queued=true}, {@code message} carries
 * the queue-entry hint).
 *
 * <p>The 3-arg constructor is the Jackson entry point for the REST
 * merge response (which has no {@code queued} field) — it defaults
 * the flag to {@code false} so existing code paths stay green.
 */
public record MergeResult(
        String sha,
        boolean merged,
        String message,
        boolean queued)
{
    @JsonCreator
    public MergeResult(
            @JsonProperty("sha") String sha,
            @JsonProperty("merged") boolean merged,
            @JsonProperty("message") String message)
    {
        this(sha, merged, message, false);
    }

    /** Build the success record returned after an {@code enqueuePullRequest}
     *  GraphQL mutation. The PR isn't merged yet — it joined the queue
     *  and will merge whenever its slot reaches the head. */
    public static MergeResult enqueued(String message)
    {
        return new MergeResult(null, false, message, true);
    }
}
