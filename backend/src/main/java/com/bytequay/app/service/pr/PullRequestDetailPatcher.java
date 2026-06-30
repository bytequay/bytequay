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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.StoredPrDetail;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class PullRequestDetailPatcher
{
    static final Set<String> ALLOWED_REACTION_CONTENT = Set.of(
            "+1", "-1", "laugh", "confused", "heart", "hooray", "rocket", "eyes");

    private PullRequestDetailPatcher() {}

    static StoredPrDetail withReviewThreadReplyAppended(StoredPrDetail detail, PrReviewThreadMessage reply)
    {
        List<PrReviewThreadMessage> patched = ImmutableList.<PrReviewThreadMessage>builder()
                .addAll(detail.reviewComments())
                .add(reply)
                .build();
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    static StoredPrDetail withTimelineCommentAppended(StoredPrDetail detail, PrTimelineEvent comment)
    {
        // Drop any existing row with the same id first so a concurrent
        // background poll that already pulled this comment in doesn't
        // leave a duplicate.
        List<PrTimelineEvent> patched = ImmutableList.<PrTimelineEvent>builder()
                .addAll(detail.timeline().stream()
                        .filter(event -> !(event.githubId() != null && comment.githubId() != null
                                && event.githubId().equals(comment.githubId())
                                && "commented".equals(event.event())))
                        .collect(toImmutableList()))
                .add(comment)
                .build();
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), patched,
                detail.checkRuns(), detail.reviewComments(), detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    static StoredPrDetail withTimelineCommentBody(StoredPrDetail detail, long commentId, String body)
    {
        List<PrTimelineEvent> patched = detail.timeline().stream()
                .map(event -> event.githubId() != null && event.githubId() == commentId && "commented".equals(event.event())
                        ? new PrTimelineEvent(
                                event.githubId(), event.event(), event.actor(), event.state(), event.timestamp(), body,
                                event.beforeSha(), event.afterSha(), event.requestedReviewer(), event.reviewId(),
                                event.authorAssociation(), event.reactions())
                        : event)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), patched,
                detail.checkRuns(), detail.reviewComments(), detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    static StoredPrDetail withReviewCommentBody(StoredPrDetail detail, long commentId, String body)
    {
        List<PrReviewThreadMessage> patched = detail.reviewComments().stream()
                .map(message -> message.githubId() == commentId
                        ? new PrReviewThreadMessage(
                                message.githubId(), message.inReplyTo(), message.reviewId(), message.author(), body,
                                message.filePath(), message.lineNumber(), message.side(), message.diffHunk(), message.commitId(),
                                message.createdAt(), message.reactions(), message.outdated(), message.startLine(), message.startSide(),
                                message.originalLine(), message.originalStartLine(), message.authorAssociation(),
                                message.graphqlNodeId(), message.resolved())
                        : message)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    static StoredPrDetail withTimelineCommentRemoved(StoredPrDetail detail, long commentId)
    {
        List<PrTimelineEvent> patched = detail.timeline().stream()
                .filter(event -> !(event.githubId() != null && event.githubId() == commentId
                        && "commented".equals(event.event())))
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), patched,
                detail.checkRuns(), detail.reviewComments(), detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    static StoredPrDetail withReviewCommentRemoved(StoredPrDetail detail, long commentId)
    {
        List<PrReviewThreadMessage> patched = detail.reviewComments().stream()
                .filter(message -> message.githubId() != commentId)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    static StoredPrDetail withReviewThreadResolved(StoredPrDetail detail, long rootCommentId, boolean resolved)
    {
        List<PrReviewThreadMessage> patched = detail.reviewComments().stream()
                .map(message -> message.githubId() == rootCommentId
                        ? new PrReviewThreadMessage(
                                message.githubId(), message.inReplyTo(), message.reviewId(), message.author(), message.body(),
                                message.filePath(), message.lineNumber(), message.side(), message.diffHunk(), message.commitId(),
                                message.createdAt(), message.reactions(), message.outdated(), message.startLine(), message.startSide(),
                                message.originalLine(), message.originalStartLine(), message.authorAssociation(),
                                message.graphqlNodeId(), resolved)
                        : message)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    static StoredPrDetail withReviewCommentReaction(StoredPrDetail detail, long commentId, String content)
    {
        List<PrReviewThreadMessage> patched = detail.reviewComments().stream()
                .map(message -> message.githubId() == commentId
                        ? new PrReviewThreadMessage(
                                message.githubId(), message.inReplyTo(), message.reviewId(), message.author(), message.body(),
                                message.filePath(), message.lineNumber(), message.side(), message.diffHunk(), message.commitId(),
                                message.createdAt(), bumpReaction(message.reactions(), content), message.outdated(),
                                message.startLine(), message.startSide(), message.originalLine(), message.originalStartLine(),
                                message.authorAssociation(), message.graphqlNodeId(), message.resolved())
                        : message)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    static StoredPrDetail withTimelineCommentReaction(StoredPrDetail detail, long commentId, String content)
    {
        List<PrTimelineEvent> patched = detail.timeline().stream()
                .map(event -> event.githubId() != null && event.githubId() == commentId && "commented".equals(event.event())
                        ? new PrTimelineEvent(
                                event.githubId(), event.event(), event.actor(), event.state(), event.timestamp(), event.body(),
                                event.beforeSha(), event.afterSha(), event.requestedReviewer(), event.reviewId(),
                                event.authorAssociation(), bumpReaction(event.reactions(), content))
                        : event)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), patched,
                detail.checkRuns(), detail.reviewComments(), detail.linkedIssues(), detail.mergeQueueState(), detail.mergeQueueEnabled());
    }

    private static Reactions bumpReaction(Reactions reactions, String content)
    {
        Reactions base = reactions == null ? Reactions.EMPTY : reactions;
        return switch (content) {
            case "+1" -> new Reactions(base.plusOne() + 1, base.minusOne(), base.laugh(), base.hooray(),
                    base.confused(), base.heart(), base.rocket(), base.eyes());
            case "-1" -> new Reactions(base.plusOne(), base.minusOne() + 1, base.laugh(), base.hooray(),
                    base.confused(), base.heart(), base.rocket(), base.eyes());
            case "laugh" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh() + 1, base.hooray(),
                    base.confused(), base.heart(), base.rocket(), base.eyes());
            case "hooray" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray() + 1,
                    base.confused(), base.heart(), base.rocket(), base.eyes());
            case "confused" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray(),
                    base.confused() + 1, base.heart(), base.rocket(), base.eyes());
            case "heart" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray(),
                    base.confused(), base.heart() + 1, base.rocket(), base.eyes());
            case "rocket" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray(),
                    base.confused(), base.heart(), base.rocket() + 1, base.eyes());
            case "eyes" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray(),
                    base.confused(), base.heart(), base.rocket(), base.eyes() + 1);
            default -> base;
        };
    }
}
