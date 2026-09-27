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
package com.bytequay.core.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

/**
 * A timestamped GitHub action, without its locally observed refresh time. A comment or review keeps
 * the start of its text, and an inline review comment the file and line it was left on.
 */
public record PrActivitySummary(
        String event,
        Optional<String> actor,
        Instant timestamp,
        Optional<String> state,
        Optional<String> label,
        Optional<String> requestedReviewer,
        Optional<String> excerpt,
        Optional<String> path,
        OptionalInt line)
{
    private static final int EXCERPT_LENGTH = 280;

    public PrActivitySummary
    {
        requireNonNull(event, "event is null");
        requireNonNull(actor, "actor is null");
        requireNonNull(timestamp, "timestamp is null");
        requireNonNull(state, "state is null");
        requireNonNull(label, "label is null");
        requireNonNull(requestedReviewer, "requestedReviewer is null");
        requireNonNull(excerpt, "excerpt is null");
        requireNonNull(path, "path is null");
        requireNonNull(line, "line is null");
    }

    /** The start of a comment's text, enough for a card to quote; blank text has none. */
    public static Optional<String> excerpt(String body)
    {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        String text = body.strip();
        if (text.length() <= EXCERPT_LENGTH) {
            return Optional.of(text);
        }
        int end = Character.isHighSurrogate(text.charAt(EXCERPT_LENGTH - 1)) ? EXCERPT_LENGTH - 1 : EXCERPT_LENGTH;
        return Optional.of(text.substring(0, end) + "…");
    }
}
