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

record GitHubIssueItem(
        long id,
        int number,
        String title,
        String state,
        User user,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("pull_request") Object pullRequest,
        List<Label> labels)
{
    record User(String login) {}

    record Label(String name, String color) {}
}
