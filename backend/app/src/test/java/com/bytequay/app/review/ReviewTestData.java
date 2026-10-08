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

import com.bytequay.core.review.ReviewCandidate;
import com.bytequay.core.review.ReviewPerspectiveAssessment;
import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

public final class ReviewTestData
{
    private ReviewTestData() {}

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
