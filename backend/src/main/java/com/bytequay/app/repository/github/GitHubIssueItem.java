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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * GitHub issue item payload.
 *
 * @param body Markdown body. List callers should ignore it because GitHub
 * often omits it from list responses.
 */
record GitHubIssueItem(
        long id,
        int number,
        String title,
        String body,
        String state,
        int comments,
        User user,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("closed_at") Instant closedAt,
        @JsonProperty("pull_request") Object pullRequest,
        List<Label> labels,
        List<User> assignees,
        Milestone milestone)
{
    /** GitHub returns the avatar URL on the user payload too — we
     *  carry it so the detail page can render the assignee row
     *  without a second fetch. Null on tests / legacy mocks. */
    record User(String login, @JsonProperty("avatar_url") String avatarUrl) {}

    record Label(String name, String color) {}

    record Milestone(String title, String state) {}
}
