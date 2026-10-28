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
package com.bytequay.integration.gitlab.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record GitLabIssueResponse(Long id, Integer iid, @JsonProperty("project_id") Long projectId, String title, String description, String state,
        @JsonProperty("web_url") String webUrl, GitLabIssueUser author, List<String> labels, List<GitLabIssueUser> assignees,
        @JsonProperty("user_notes_count") Integer commentCount, @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt, @JsonProperty("issue_type") String issueType) {}
