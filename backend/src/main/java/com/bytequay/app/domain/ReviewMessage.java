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
import java.util.List;

/**
 * One post in the panel transcript. The pass's conversation is the
 * ordered list of these by {@code createdAt}. Each message is bound
 * to a single participant and a single phase / round, so the panel
 * UI can group bubbles per phase and the moderator can replay just
 * the relevant subset of context for a downstream call.
 *
 * @param mentions    participant ids this message addresses
 *                    ({@code @gpt}, {@code @claude}, {@code @panel}).
 *                    Empty when the post is a broadcast.
 * @param refs        ids of earlier {@link ReviewMessage}s this post
 *                    quotes verbatim ({@code #m12}). Lets a reviewer's
 *                    next model call include a referenced message
 *                    without dragging the entire scrollback.
 * @param payloadKind shape of {@code payloadJson}: {@code "prose"}
 *                    (plain text, the default), {@code "cross_review"},
 *                    {@code "consensus"}, or {@code "debate_turn"}.
 * @param payloadJson the structured envelope as a JSON string, or null
 *                    for plain prose. Carried alongside {@code body} so
 *                    the UI can render structure and a downstream model
 *                    call can replay the claim without re-parsing prose.
 */
public record ReviewMessage(
        String id,
        String reviewPassId,
        String participantId,
        ReviewPhase phase,
        int round,
        String body,
        List<String> mentions,
        List<String> refs,
        String payloadKind,
        String payloadJson,
        long costUsdMilli,
        Instant createdAt)
{
    /** Plain-prose message — kickoff, moderator and debate text that
     *  carries no structured envelope. Keeps the many existing call
     *  sites unchanged while the canonical constructor grows the
     *  payload fields. */
    public ReviewMessage(
            String id,
            String reviewPassId,
            String participantId,
            ReviewPhase phase,
            int round,
            String body,
            List<String> mentions,
            List<String> refs,
            long costUsdMilli,
            Instant createdAt)
    {
        this(id, reviewPassId, participantId, phase, round, body, mentions, refs,
                "prose", null, costUsdMilli, createdAt);
    }
}
