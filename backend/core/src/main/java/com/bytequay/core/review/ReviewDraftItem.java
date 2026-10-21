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

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One outgoing inline comment in the user's local pending review. A promoted finding keeps the
 * finding identity and revision it was snapshotted from; its body is the user's to edit and never
 * writes back to the finding. Later finding changes do not affect this snapshot or its publication.
 */
public record ReviewDraftItem(
        String id,
        Source source,
        Optional<String> findingId,
        OptionalInt findingRevision,
        String path,
        String side,
        int line,
        OptionalInt startLine,
        String body,
        boolean included,
        Optional<ReviewPublication> publication,
        Optional<String> commitId,
        OptionalLong replyToCommentId)
{
    public ReviewDraftItem
    {
        requireNonNull(id, "id is null");
        requireNonNull(source, "source is null");
        requireNonNull(findingId, "findingId is null");
        requireNonNull(findingRevision, "findingRevision is null");
        requireNonNull(path, "path is null");
        requireNonNull(side, "side is null");
        checkArgument(line > 0, "line is not positive");
        requireNonNull(startLine, "startLine is null");
        startLine.ifPresent(value -> checkArgument(value > 0 && value <= line, "invalid line range"));
        requireNonNull(body, "body is null");
        requireNonNull(publication, "publication is null");
        requireNonNull(replyToCommentId, "replyToCommentId is null");
        replyToCommentId.ifPresent(value -> checkArgument(value > 0, "replyToCommentId is not positive"));
        checkOptionalText(commitId, "commitId");
        checkArgument((source == Source.AGENT_FINDING) == findingId.isPresent(), "findingId and source disagree");
    }

    public boolean published()
    {
        return publication.filter(value -> value.state() == ReviewPublication.State.PUBLISHED).isPresent();
    }

    public ReviewDraftItem withBody(String updatedBody)
    {
        return new ReviewDraftItem(id, source, findingId, findingRevision, path, side, line, startLine, updatedBody, included, publication, commitId, replyToCommentId);
    }

    public ReviewDraftItem withIncluded(boolean updatedIncluded)
    {
        return new ReviewDraftItem(id, source, findingId, findingRevision, path, side, line, startLine, body, updatedIncluded, publication, commitId, replyToCommentId);
    }

    public ReviewDraftItem withPublication(Optional<ReviewPublication> updatedPublication)
    {
        return new ReviewDraftItem(id, source, findingId, findingRevision, path, side, line, startLine, body, included, updatedPublication, commitId, replyToCommentId);
    }

    public ReviewDraftItem withCommitId(String updatedCommitId)
    {
        return new ReviewDraftItem(
                id, source, findingId, findingRevision, path, side, line, startLine, body, included, publication, Optional.of(updatedCommitId), replyToCommentId);
    }

    public enum Source
    {
        MANUAL,
        AGENT_FINDING
    }
}
