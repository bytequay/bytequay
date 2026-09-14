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

import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;

import java.util.Optional;

/** Stable card identities retain the reviewed run while a newer review is being produced. */
final class ReviewCards
{
    private ReviewCards() {}

    static ReviewRun requireRun(AgentReviewStore store, String reviewId, String cardId)
    {
        int separator = cardId.indexOf(':');
        if (separator <= 0) {
            throw new ReviewInvalidRequestException("cardId: expected a run-scoped summary card");
        }
        ReviewRun run = store.findRun(cardId.substring(0, separator))
                .filter(value -> value.reviewId().equals(reviewId))
                .orElseThrow(() -> new ReviewInvalidRequestException("cardId: the card does not belong to this review"));
        content(run, cardId);
        return run;
    }

    static String content(ReviewRun run, String cardId)
    {
        Optional<ReviewSummary> summary = run.summary();
        String section = cardId.substring(run.id().length() + 1);
        return switch (section) {
            case "purpose" -> summary.map(ReviewSummary::purpose).orElse("The PR summary has not been recorded yet.");
            case "combined-result" -> summary.map(ReviewSummary::combinedResult).orElse("The combined result has not been recorded yet.");
            case "evidence" -> summary.flatMap(ReviewSummary::evidence).orElse("No separate supporting evidence was recorded. Consult findings for their cited evidence.");
            case "critical-issues" -> summary.filter(value -> !value.criticalFindingIds().isEmpty())
                    .map(value -> String.join("\n", value.criticalFindingIds())).orElse("No critical findings were recorded.");
            case "coverage" -> run.coverage().map(coverage -> "Complete: " + coverage.complete() + "\nOmissions: " + coverage.omissions())
                    .orElse("Coverage has not been recorded.");
            default -> {
                if (!section.startsWith("commit:")) {
                    throw new ReviewInvalidRequestException("cardId: unknown summary card");
                }
                String sha = section.substring("commit:".length());
                String subject = run.commits().stream().filter(commit -> commit.sha().equals(sha)).map(commit -> commit.message()).findFirst()
                        .orElseThrow(() -> new ReviewInvalidRequestException("cardId: unknown commit in this run"));
                yield subject + "\n" + summary.stream().flatMap(value -> value.commits().stream()).filter(commit -> commit.sha().equals(sha))
                        .map(ReviewSummary.CommitExplanation::explanation).findFirst().orElse("The commit explanation has not been recorded yet.");
            }
        };
    }
}
