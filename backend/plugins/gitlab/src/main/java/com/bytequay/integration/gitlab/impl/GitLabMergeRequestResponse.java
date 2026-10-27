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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record GitLabMergeRequestResponse(
        Long id,
        Long iid,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("target_project_id") Long targetProjectId,
        String title,
        String state,
        @JsonProperty("web_url") String webUrl,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("closed_at") String closedAt,
        @JsonProperty("merged_at") String mergedAt,
        @JsonDeserialize(using = DraftDeserializer.class) Boolean draft,
        @JsonProperty("work_in_progress") Boolean workInProgress,
        String description,
        User author,
        List<String> labels,
        List<User> assignees,
        Milestone milestone,
        @JsonProperty("source_branch") String sourceBranch,
        @JsonProperty("target_branch") String targetBranch,
        String sha,
        @JsonProperty("diff_refs") DiffRefs diffRefs,
        @JsonProperty("changes_count") String changesCount,
        @JsonProperty("detailed_merge_status") String detailedMergeStatus)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(String username, @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Milestone(String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DiffRefs(@JsonProperty("head_sha") String headSha, @JsonProperty("start_sha") String startSha) {}

    /** Explicit null is malformed; only a missing draft field can use the older work_in_progress field. */
    static final class DraftDeserializer
            extends JsonDeserializer<Boolean>
    {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context)
                throws IOException
        {
            return parser.getBooleanValue();
        }

        @Override
        public Boolean getNullValue(DeserializationContext context)
                throws JsonMappingException
        {
            return context.reportInputMismatch(Boolean.class, "GitLab draft must not be null");
        }

        @Override
        public Boolean getAbsentValue(DeserializationContext context)
        {
            return null;
        }
    }
}
