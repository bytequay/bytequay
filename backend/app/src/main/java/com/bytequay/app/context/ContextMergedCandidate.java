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
package com.bytequay.app.context;

import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.core.context.ContextLimits;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Frozen, bounded local context offered for one explicitly selected merged pull request. */
public record ContextMergedCandidate(
        String id, String repo, int number, String title, String url, Instant mergedAt,
        Optional<String> taskId, Optional<String> taskTitle, Optional<String> taskResult,
        Optional<DevelopmentLearningSummary> learningSummary, Optional<Issue> issue,
        Optional<Review> review, List<Check> checks, List<String> gaps)
{
    public ContextMergedCandidate
    {
        ContextLimits.text(id, "candidate id", 200);
        ContextLimits.text(repo, "candidate repository", 200);
        checkArgument(number > 0, "Invalid candidate PR number");
        ContextLimits.text(title, "candidate title", 500);
        ContextLimits.text(url, "candidate URL", 500);
        requireNonNull(mergedAt, "mergedAt is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(taskTitle, "taskTitle is null");
        requireNonNull(taskResult, "taskResult is null");
        requireNonNull(learningSummary, "learningSummary is null");
        requireNonNull(issue, "issue is null");
        requireNonNull(review, "review is null");
        checks = ImmutableList.copyOf(checks);
        gaps = ImmutableList.copyOf(gaps);
        checkArgument(checks.size() <= 20 && gaps.size() <= 20, "Candidate evidence limit reached");
    }

    public record Issue(String kind, String key, String url, String title, String context)
    {
        public Issue
        {
            ContextLimits.text(kind, "issue kind", 20);
            ContextLimits.text(key, "issue key", 100);
            ContextLimits.text(url, "issue URL", 500);
            ContextLimits.text(title, "issue title", 500);
            ContextLimits.text(context, "issue context", 4000);
        }
    }

    public record Review(String reviewId, String runId, String purpose, String combinedResult,
            boolean coverageComplete, List<String> omissions, List<String> warnings)
    {
        public Review
        {
            ContextLimits.text(reviewId, "review ID", 200);
            ContextLimits.text(runId, "review run ID", 200);
            ContextLimits.text(purpose, "review purpose", 4000);
            ContextLimits.text(combinedResult, "review result", 4000);
            omissions = ImmutableList.copyOf(omissions);
            warnings = ImmutableList.copyOf(warnings);
            checkArgument(omissions.size() <= 20 && warnings.size() <= 20, "Review coverage limit reached");
        }
    }

    public record Check(String name, String status, String conclusion, String url)
    {
        public Check
        {
            ContextLimits.text(name, "check name", 400);
            ContextLimits.text(status, "check status", 100);
            ContextLimits.text(conclusion, "check conclusion", 100);
            ContextLimits.text(url, "check URL", 500);
        }
    }
}
