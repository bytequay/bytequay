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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * GitHub timeline event payload.
 *
 * @param authorAssociation author's relationship to the repo for commented and
 * reviewed timeline events.
 * @param label label payload for labeled and unlabeled events.
 * @param assignee assignee payload for assigned and unassigned events.
 * @param milestone milestone payload for milestoned and demilestoned events.
 * @param rename title diff payload for renamed events.
 * @param source referencing issue or PR payload for cross-referenced events.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTimelineEvent(
        // For "reviewed" events this is the GitHub review ID — the same id
        // referenced by per-line review comments via pull_request_review_id.
        // For other event types the field is the timeline event's id, not
        // useful for cross-linking; safe to ignore.
        Long id,
        String event,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("submitted_at") Instant submittedAt,
        Actor actor,
        User user,
        String state,
        String message,
        // "commented" and "reviewed" timeline events carry their text payload
        // here; absent for structural events like "review_requested".
        String body,
        // "committed" uses sha; "merged" uses commit_id for the same value.
        @JsonAlias("commit_id")
        String sha,
        // head_ref_force_pushed events carry these — null otherwise.
        @JsonProperty("before_commit") Commit beforeCommit,
        @JsonProperty("after_commit") Commit afterCommit,
        Author author,
        @JsonProperty("requested_reviewer") User requestedReviewer,
        @JsonProperty("author_association") String authorAssociation,
        Label label,
        User assignee,
        Milestone milestone,
        Rename rename,
        Source source)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Actor(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(String name, Instant date) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(String sha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(String name, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Milestone(String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rename(String from, String to) {}

    /** "cross-referenced" payload. {@code issue} can carry either a
     *  plain issue or a PR — the {@code pullRequest} sub-object on it
     *  is what discriminates the two in GitHub's REST API. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String type, SourceIssue issue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceIssue(
            int number,
            String title,
            String state,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("pull_request") PullRequestStub pullRequest,
            Repository repository) {}

    /** Presence on a {@link SourceIssue} tells us the row is a PR
     *  rather than an issue; the contents aren't useful in v1. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestStub() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(@JsonProperty("full_name") String fullName) {}
}
