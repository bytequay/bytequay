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

import java.time.Instant;
import java.util.List;

/**
 * JSON shape for one entry returned by GitHub's
 * {@code GET /repos/{owner}/{repo}/events}. The payload shape varies by
 * event type — Jackson tolerates the union via {@link JsonIgnoreProperties}
 * and per-field optionality. Only the fields the activity-feed renderer
 * needs are declared.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record GitHubRepoEvent(
        String type,
        Actor actor,
        Payload payload,
        @JsonProperty("created_at") Instant createdAt)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Actor(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(
            String action,
            String ref,
            @JsonProperty("ref_type") String refType,
            @JsonProperty("pull_request") PullRequestRefDto pullRequest,
            IssueRefDto issue,
            ReleaseRefDto release,
            List<CommitRef> commits) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestRefDto(int number, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueRefDto(int number, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReleaseRefDto(@JsonProperty("tag_name") String tagName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitRef(String sha) {}
}
