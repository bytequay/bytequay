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

import java.util.List;

/**
 * The execution state of a single CI check run.
 *
 * @param name          human-readable name of the check (e.g. "build", "test-unit")
 * @param status        lifecycle state — {@code queued}, {@code in_progress}, {@code completed}
 * @param conclusion    outcome once completed — {@code success}, {@code failure},
 *                      {@code neutral}, {@code cancelled}, {@code timed_out},
 *                      {@code action_required}, {@code skipped}, or {@code null}
 *                      while still running
 * @param htmlUrl       link to the check's details page on GitHub (may be null)
 * @param outputTitle   short one-liner from GitHub's {@code output.title} —
 *                      e.g. "5 tests failed". Null when the runner doesn't
 *                      publish an output block.
 * @param outputSummary longer markdown summary from {@code output.summary} —
 *                      usually the actual error excerpt the runner attached.
 *                      Null when absent.
 */
public record PrCheckRunState(
        Long githubId,
        String name,
        String status,
        String conclusion,
        String htmlUrl,
        String outputTitle,
        String outputSummary,
        GitHubMetadata githubMetadata)
{
    /** Compatibility constructor for cached/UI callers that do not need proof metadata. */
    public PrCheckRunState(
            Long githubId,
            String name,
            String status,
            String conclusion,
            String htmlUrl,
            String outputTitle,
            String outputSummary)
    {
        this(githubId, name, status, conclusion, htmlUrl, outputTitle,
                outputSummary, null);
    }

    /** GitHub-only lineage retained by exact Remote CI observations. */
    public record GitHubMetadata(
            String testedSha,
            String externalId,
            String detailsUrl,
            Long checkSuiteId,
            Long appId,
            String appSlug,
            Integer annotationCount,
            List<PullRequestSubject> pullRequests)
    {
        public GitHubMetadata
        {
            pullRequests = pullRequests == null
                    ? List.of() : List.copyOf(pullRequests);
        }
    }

    public record PullRequestSubject(
            int pullRequestNumber, String headSha, String baseSha) {}
}
