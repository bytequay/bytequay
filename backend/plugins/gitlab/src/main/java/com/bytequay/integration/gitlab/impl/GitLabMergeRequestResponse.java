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

import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;

@JsonIgnoreProperties(ignoreUnknown = true)
record GitLabMergeRequestResponse(
        Long id,
        Long iid,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("source_project_id") Long sourceProjectId,
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
        GitLabUser author,
        List<String> labels,
        List<GitLabUser> assignees,
        List<GitLabUser> reviewers,
        Milestone milestone,
        @JsonProperty("source_branch") String sourceBranch,
        @JsonProperty("target_branch") String targetBranch,
        String sha,
        @JsonProperty("merge_commit_sha") String mergeCommitSha,
        @JsonProperty("diff_refs") DiffRefs diffRefs,
        @JsonProperty("head_pipeline") Pipeline headPipeline,
        @JsonProperty("changes_count") String changesCount,
        @JsonProperty("merge_status") String mergeStatus,
        @JsonProperty("detailed_merge_status") String detailedMergeStatus)
{
    // Servers before the draft field report only work_in_progress.
    boolean isDraft()
    {
        return Optional.ofNullable(draft).or(() -> Optional.ofNullable(workInProgress))
                .orElseThrow(() -> new HostingFailure(INVALID_RESPONSE, "GitLab returned no merge request draft status"));
    }

    /** The app's open, closed or merged state; GitLab locks an open request while it merges. */
    String requestState()
    {
        return switch (state) {
            case "opened", "locked" -> "open";
            case "closed" -> "closed";
            case "merged" -> "merged";
            default -> throw new HostingFailure(INVALID_RESPONSE, "GitLab returned an invalid merge request state");
        };
    }

    Pipeline requireHeadPipeline()
    {
        return Optional.ofNullable(headPipeline).orElseThrow(() -> new HostingFailure(REVISION_CONFLICT, "This request has no current pipeline"));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Milestone(String title) {}

    /** start_sha is the target tip; base_sha is the merge base. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DiffRefs(@JsonProperty("head_sha") String headSha, @JsonProperty("start_sha") String startSha, @JsonProperty("base_sha") String baseSha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pipeline(long id, @JsonProperty("project_id") Long projectId, String status, @JsonProperty("web_url") String webUrl)
    {
        /** Fork pipelines run in their source project, not the request's target project. */
        String projectPath(HostingRepository target)
        {
            return "projects/" + (projectId == null ? encode(target.remoteId()) : projectId);
        }

        String path(HostingRepository target)
        {
            return projectPath(target) + "/pipelines/" + id;
        }
    }

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
