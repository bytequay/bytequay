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
    HostingProvider provider();

    PrTimelineEvent comment(HostingAccess access, HostingRepository repository, int number, String body);

    PrTimelineEvent createReviewComment(HostingAccess access, HostingRepository repository, int number, InlineReviewComment comment);

    void replyToReviewComment(HostingAccess access, HostingRepository repository, int number, long commentId, String body);

    void editComment(HostingAccess access, HostingRepository repository, int number, String target, long commentId, String body);

    boolean toggleReaction(HostingAccess access, HostingRepository repository, int number, String target, Long commentId, String content, Boolean viewerHasReacted);

    void updateTitle(HostingAccess access, HostingRepository repository, int number, String title);

    void updateDescription(HostingAccess access, HostingRepository repository, int number, String description);

    void close(HostingAccess access, HostingRepository repository, int number);

    void markReady(HostingAccess access, HostingRepository repository, int number);

    void updateMetadata(HostingAccess access, HostingRepository repository, int number, List<String> assignees, List<String> labels);

    void updateReviewer(HostingAccess access, HostingRepository repository, int number, String login, boolean requested);

    void resolveThread(HostingAccess access, HostingRepository repository, int number, long commentId, boolean resolved);

    SubmittedReview submitReview(HostingAccess access, HostingRepository repository, int number, String event, String body, List<InlineReviewComment> comments, String commitId);

    MergeResult merge(HostingAccess access, HostingRepository repository, int number, String strategy, String headSha);

    void rerunFailedChecks(HostingAccess access, HostingRepository repository, int number);

    void removeFromMergeQueue(HostingAccess access, HostingRepository repository, int number, boolean autoMerge);

    boolean hasMergeQueue(HostingAccess access, HostingRepository repository, String baseRef);

    CreatedPullRequest create(HostingAccess access, HostingRepository repository, String head, String base, String title, String body, boolean draft);
}
