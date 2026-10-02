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
package com.bytequay.core.memory;

import com.bytequay.core.context.ContextLimits;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Where a memory came from, with a short quote of the words it rests on. ByteQuay binds every source; a model never names one
 * it was not given.
 *
 * @param url the comment, review or pull request on GitHub, or the page address
 * @param at when the comment was written or the page was read
 * @param pullRequest the pull request part, for a {@link Kind#PULL_REQUEST} source
 * @param section the page section, for a {@link Kind#PAGE} source
 */
public record MemorySource(
        Kind kind,
        String url,
        String quote,
        Instant at,
        Optional<PullRequestPart> pullRequest,
        Optional<String> section)
{
    public static final int MAX_QUOTE_BYTES = 300;

    public enum Kind
    {
        PULL_REQUEST,
        PAGE,
    }

    public MemorySource
    {
        requireNonNull(kind, "kind is null");
        ContextLimits.text(url, "source url", 2048);
        checkArgument("https".equals(URI.create(url).getScheme()), "source url must use HTTPS");
        requireNonNull(quote, "quote is null");
        checkArgument(ContextLimits.bytes(quote) <= MAX_QUOTE_BYTES, "source quote exceeds %s UTF-8 bytes", MAX_QUOTE_BYTES);
        requireNonNull(at, "at is null");
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(section, "section is null");
        checkArgument(pullRequest.isPresent() == (kind == Kind.PULL_REQUEST), "only a pull request source has a pull request part");
        checkArgument(kind == Kind.PAGE || section.isEmpty(), "only a page source has a section");
        checkArgument(kind == Kind.PAGE || !quote.isBlank(), "a pull request source quotes the words it rests on");
        section.ifPresent(value -> ContextLimits.text(value, "source section", 200));
    }

    /**
     * One comment, review or close on a pull request.
     *
     * @param commentId GitHub's id of the comment or review, unique within the repository
     * @param maintainer whether GitHub reported the author as an owner, member or collaborator
     * @param resolved whether the review thread was resolved
     * @param outdated whether the code under the review thread changed afterwards
     */
    public record PullRequestPart(int number, Part part, String commentId, String author, boolean maintainer, boolean merged, boolean resolved, boolean outdated)
    {
        public PullRequestPart
        {
            checkArgument(number > 0, "pull request number must be positive");
            requireNonNull(part, "part is null");
            checkText(commentId, "commentId", 200);
            checkText(author, "author", 100);
        }
    }

    public enum Part
    {
        REVIEW_COMMENT,
        REVIEW,
        COMMENT,
        CLOSE,
    }
}
