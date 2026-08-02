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
package com.bytequay.app.repository.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCheckRunsResponse(
        @JsonProperty("total_count") int totalCount,
        @JsonProperty("check_runs") List<CheckRun> checkRuns)
{
    /**
     * GitHub check-run response item.
     *
     * @param id stable per-attempt id.
     * @param output GitHub's per-run summary block.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckRun(
            Long id,
            @JsonProperty("head_sha") String headSha,
            @JsonProperty("external_id") String externalId,
            String name,
            String status,
            String conclusion,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("details_url") String detailsUrl,
            @JsonProperty("check_suite") CheckSuite checkSuite,
            App app,
            @JsonProperty("pull_requests") List<PullRequestSubject> pullRequests,
            CheckRunOutput output) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckRunOutput(
            String title,
            String summary,
            @JsonProperty("annotations_count") Integer annotationsCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckSuite(Long id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record App(Long id, String slug) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestSubject(
            int number, CommitSubject head, CommitSubject base) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitSubject(String sha) {}
}
