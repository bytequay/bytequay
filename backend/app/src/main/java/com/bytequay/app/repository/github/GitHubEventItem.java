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

@JsonIgnoreProperties(ignoreUnknown = true)
record GitHubEventItem(
        String type,
        Actor actor,
        Repo repo,
        Payload payload,
        @JsonProperty("created_at") Instant createdAt)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Actor(String login, @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repo(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(
            Integer size,
            String action,
            @JsonProperty("ref_type") String refType,
            String ref,
            @JsonProperty("pull_request") PrPayload pullRequest,
            IssuePayload issue,
            ReviewPayload review,
            List<CommitPayload> commits) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PrPayload(int number, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssuePayload(int number, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReviewPayload(String state) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitPayload(String message) {}
}
