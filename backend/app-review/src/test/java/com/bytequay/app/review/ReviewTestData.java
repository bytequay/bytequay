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
package com.bytequay.app.review;

import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewCandidate;
import com.bytequay.core.review.ReviewPerspectiveAssessment;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.google.common.collect.ImmutableList.toImmutableList;

public final class ReviewTestData
{
    private ReviewTestData() {}

    public static AgentReview pullRequestReview(String id, String workspaceId, String repo, int number, String taskId, Instant now)
    {
        return openReview(id, workspaceId, repo, OptionalInt.of(number), taskId, Optional.empty(), now);
    }

    public static AgentReview openReview(String id, String workspaceId, String repo, OptionalInt number, String taskId, Optional<String> sourceTaskId, Instant now)
    {
        return new AgentReview(id, workspaceId, repo, number, OptionalLong.empty(), taskId, sourceTaskId, AgentReview.State.OPEN,
                false, Optional.empty(), Optional.empty(), Optional.empty(), 0, now, now, Optional.empty(), Optional.empty());
    }

    public static AgentReview withSource(
            AgentReview review, OptionalInt number, OptionalLong pullRequestId, Optional<String> sourceTaskId, Optional<AgentReview.Replay> replay)
    {
        return new AgentReview(review.id(), review.workspaceId(), review.repo(), number, pullRequestId, review.taskId(), sourceTaskId, review.state(),
                review.followUpPaused(), review.latestCompletedRunId(), review.observedHeadSha(), review.worktreeHeadSha(), review.version(),
                review.createdAt(), review.updatedAt(), review.closedAt(), replay);
    }

    public static ReviewCandidate discoveryCandidate(int number, String hypothesis)
    {
        return discoveryCandidate(number, hypothesis, Optional.empty());
    }

    public static ReviewCandidate discoveryCandidate(int number, String hypothesis, Optional<String> carriedFindingId)
    {
        return new ReviewCandidate(number, ReviewPerspectiveAssessment.Perspective.BEHAVIOR, "Parser ownership", hypothesis,
                ImmutableList.of("E1"), "Inspect the caller cleanup path", carriedFindingId, 1, "investigation-turn", Optional.empty());
    }

    public static List<ReviewPerspectiveAssessment> perspectives()
    {
        return Arrays.stream(ReviewPerspectiveAssessment.Perspective.values())
                .map(perspective -> new ReviewPerspectiveAssessment(perspective, "Which parser paths change?", ImmutableList.of("Parser ownership"),
                        ImmutableList.of("src/App.java"), ImmutableList.of(), ReviewPerspectiveAssessment.Status.CHECKED, "Checked the relevant parser paths"))
                .collect(toImmutableList());
    }
}
