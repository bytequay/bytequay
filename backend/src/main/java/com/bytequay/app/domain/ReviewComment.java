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
import java.util.UUID;

/**
 * A unified inline review comment on a Task's diff, across all sources.
 * {@code remoteLink} and {@code remoteCommentId} are populated iff
 * {@code source} is {@link ReviewCommentSource#REMOTE_REVIEWER} — a DB
 * check constraint enforces the {@code remoteLink} half of that invariant.
 *
 * <p>{@code roundId} groups a remote comment into a {@code ReviewRound}
 * batch (null until {@code ReviewRoundService} assigns it).
 * {@code draftReplyBody} is the round agent's locally-drafted reply —
 * nothing posts to GitHub until the round's gate approval reads it and
 * calls {@code PullRequestService.replyToReviewThread} with
 * {@code remoteCommentId} as the thread root.
 *
 * @param file relative path
 * @param body markdown
 * @param remoteLink github.com discussion link, non-null iff remote-sourced
 * @param remoteCommentId the raw GitHub comment id, non-null iff remote-sourced
 * @param roundId the {@code ReviewRound} this comment is grouped into, if any
 * @param draftReplyBody the round agent's drafted (unposted) reply, if any
 * @param draftReplyCreatedAt when the draft reply was recorded, if any
 * @param draftReplyPostedAt when the draft reply was successfully posted, if any
 * @param side {@link DiffSide#LEFT} or {@link DiffSide#RIGHT} — defaults to
 *             RIGHT for every comment that predates this concept
 * @param startLine first line of a multi-line range, null for single-line
 * @param startSide diff side of {@code startLine}, null for single-line
 */
public record ReviewComment(
        UUID id,
        String taskId,
        String file,
        int line,
        String body,
        Instant createdAt,
        ReviewCommentSource source,
        String remoteLink,
        boolean resolved,
        Long remoteCommentId,
        UUID roundId,
        String draftReplyBody,
        Instant draftReplyCreatedAt,
        Instant draftReplyPostedAt,
        String side,
        Integer startLine,
        String startSide)
{
    /** Compatibility constructor for callers creating a not-yet-posted reply. */
    public ReviewComment(
            UUID id,
            String taskId,
            String file,
            int line,
            String body,
            Instant createdAt,
            ReviewCommentSource source,
            String remoteLink,
            boolean resolved,
            Long remoteCommentId,
            UUID roundId,
            String draftReplyBody,
            Instant draftReplyCreatedAt,
            String side,
            Integer startLine,
            String startSide)
    {
        this(id, taskId, file, line, body, createdAt, source, remoteLink, resolved,
                remoteCommentId, roundId, draftReplyBody, draftReplyCreatedAt,
                null, side, startLine, startSide);
    }

    public ReviewComment withDraftReplyPostedAt(Instant postedAt)
    {
        return new ReviewComment(
                id, taskId, file, line, body, createdAt, source, remoteLink, resolved,
                remoteCommentId, roundId, draftReplyBody, draftReplyCreatedAt,
                postedAt, side, startLine, startSide);
    }

    public ReviewComment withRemoteState(boolean remoteResolved, long threadRootCommentId)
    {
        return new ReviewComment(
                id, taskId, file, line, body, createdAt, source, remoteLink, remoteResolved,
                threadRootCommentId, roundId, draftReplyBody, draftReplyCreatedAt,
                draftReplyPostedAt, side, startLine, startSide);
    }
}
