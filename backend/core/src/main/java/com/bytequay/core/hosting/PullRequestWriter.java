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
package com.bytequay.core.hosting;

import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrTimelineEvent;

import java.util.List;

/** Provider operations performed only after an application-authorized write. */
public interface PullRequestWriter
{
    /** Returns the note, or a command result with no remote ID when no note was created. */
    PrTimelineEvent comment(HostingTarget target, int number, String body);

    /** Returns the thread, or a command result with no remote ID when no note was created. */
    PrTimelineEvent createReviewComment(HostingTarget target, int number, InlineReviewComment comment);

    void replyToReviewComment(HostingTarget target, int number, long commentId, String body);

    void editComment(HostingTarget target, int number, String commentKind, long commentId, String body);

    boolean toggleReaction(HostingTarget target, int number, String commentKind, Long commentId, String content, Boolean viewerHasReacted);

    void updateTitle(HostingTarget target, int number, String title);

    void updateDescription(HostingTarget target, int number, String description);

    void close(HostingTarget target, int number);

    void markReady(HostingTarget target, int number);

    void updateMetadata(HostingTarget target, int number, List<String> assignees, List<String> labels);

    void updateReviewer(HostingTarget target, int number, String login, boolean requested);

    void resolveThread(HostingTarget target, int number, long commentId, boolean resolved);

    SubmittedReview submitReview(HostingTarget target, int number, String event, String body, List<InlineReviewComment> comments, String commitId);

    MergeResult merge(HostingTarget target, int number, String strategy, String headSha);

    void rerunFailedChecks(HostingTarget target, int number);

    void removeFromMergeQueue(HostingTarget target, int number, boolean autoMerge);

    boolean hasMergeQueue(HostingTarget target, String baseRef);

    CreatedPullRequest create(HostingTarget target, String head, String base, String title, String body, boolean draft);
}
