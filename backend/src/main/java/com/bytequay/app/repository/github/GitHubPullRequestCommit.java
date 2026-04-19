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

import java.time.Instant;

/**
 * Wire-format record for a single commit in
 * {@code GET /repos/{owner}/{repo}/pulls/{pull_number}/commits}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestCommit(
        String sha,
        CommitInfo commit,
        Author author)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitInfo(
            String message,
            GitSignature author) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitSignature(
            String name,
            String email,
            Instant date) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(
            String login) {}
}
