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
        // "committed" events carry the commit SHA at the top level.
        String sha,
        // head_ref_force_pushed events carry these — null otherwise.
        @JsonProperty("before_commit") Commit beforeCommit,
        @JsonProperty("after_commit") Commit afterCommit,
        Author author,
        @JsonProperty("requested_reviewer") User requestedReviewer,
        /** Author's relationship to the repo for "commented" / "reviewed"
         *  timeline events: MEMBER / CONTRIBUTOR / OWNER / NONE / …. */
        @JsonProperty("author_association") String authorAssociation)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Actor(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(String name, Instant date) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(String sha) {}
}
