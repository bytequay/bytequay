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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The passage a discussion is attached to.
 *
 * <p>{@code quote} is stored exactly as the reader saw it, which is rendered text rather than
 * markdown source, so the thread still renders once the answer has left the loaded transcript
 * window. It is always presented as text, never as trusted markup.
 *
 * <p>No label is stored. The reader-facing label is presentation, derived in the renderer from
 * the quote and the occurrence, for the same reason a block ordinal is not stored.
 *
 * @param turnId     the completed turn whose final answer was quoted
 * @param quote      the highlighted text as rendered, verified against that answer
 * @param occurrence which appearance of the quote in that answer was highlighted, 1-based; the
 *         client knows it from the selection's position, and it is identity only
 * @param quoteHash  the stable identity of the passage, which makes one discussion per passage a
 *         database fact; it covers the normalized quote and the occurrence
 */
public record DiscussionAnchor(String turnId, String quote, int occurrence, String quoteHash)
{
    public DiscussionAnchor
    {
        requireNonNull(turnId, "turnId is null");
        requireNonNull(quote, "quote is null");
        requireNonNull(quoteHash, "quoteHash is null");
        checkArgument(!turnId.isBlank() && turnId.length() <= 100, "Invalid anchor turn id");
        checkArgument(!quote.isBlank() && quote.length() <= 16_000, "Quote must contain 1-16000 characters");
        checkArgument(occurrence >= 1, "Invalid quote occurrence");
        checkArgument(quoteHash.matches("[0-9a-f]{64}"), "Invalid quote hash");
    }
}
