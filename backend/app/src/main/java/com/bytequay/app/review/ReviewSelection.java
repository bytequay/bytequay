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

import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;

import java.util.List;

/** Recommendation membership comes from the accepted report, never finding arrival order. */
public final class ReviewSelection
{
    private ReviewSelection() {}

    public static boolean selected(ReviewFinding finding, List<ReviewRun> runs)
    {
        return finding.origin() == ReviewFinding.Origin.AUTOMATIC && runs.stream()
                .filter(run -> run.id().equals(finding.runId()) && run.reviewId().equals(finding.reviewId()) && run.state() == ReviewRun.State.ACCEPTED)
                .flatMap(run -> run.currentStep().flatMap(ReviewStep::validation).stream())
                .anyMatch(validation -> validation.selectedFindingIds().contains(finding.id()));
    }
}
