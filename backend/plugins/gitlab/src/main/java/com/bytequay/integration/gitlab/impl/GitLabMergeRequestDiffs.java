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

import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.DiffRefs;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static java.util.Objects.requireNonNull;

final class GitLabMergeRequestDiffs
{
    private final GitLabHttpClient http;

    GitLabMergeRequestDiffs(GitLabHttpClient http)
    {
        this.http = requireNonNull(http, "http is null");
    }

    List<GitLabFileDiff> read(HostingAccess access, String mergeRequestPath)
    {
        ImmutableList.Builder<GitLabFileDiff> files = ImmutableList.builder();
        for (int page = 1; ; page++) {
            GitLabHttpClient.Response<GitLabFileDiff[]> response;
            try {
                response = http.get(access, mergeRequestPath + "/diffs?per_page=100&page=" + page, Optional.empty(), GitLabFileDiff[].class);
            }
            catch (HostingFailure failure) {
                if (page != 1 || failure.kind() != MISSING_OR_INACCESSIBLE) {
                    throw failure;
                }
                return legacyChanges(access, mergeRequestPath);
            }
            files.addAll(validateFiles(Arrays.asList(response.body().orElseThrow())));
            if (!response.hasNext()) {
                return files.build();
            }
        }
    }

    private List<GitLabFileDiff> legacyChanges(HostingAccess access, String path)
    {
        // A missing modern endpoint is not proof of an old server; independently verify the requested merge request.
        GitLabMergeRequestResponse current = http.get(access, path, Optional.empty(), GitLabMergeRequestResponse.class).body().orElseThrow();
        validateIdentity(path, current.projectId(), current.iid(), current.sha());
        validateRefs(current.sha(), current.diffRefs());
        Changes changes = http.get(access, path + "/changes", Optional.empty(), Changes.class).body().orElseThrow();
        validateIdentity(path, changes.projectId(), changes.iid(), changes.sha());
        validateRefs(changes.sha(), changes.diffRefs());
        if (changes.overflow() == null || changes.changes() == null) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned incomplete merge request changes. Refresh and try again.");
        }
        if (changes.overflow()) {
            throw new HostingFailure(UNAVAILABLE, "GitLab returned truncated merge request changes. Open the merge request in GitLab to view the complete diff.");
        }
        if (!current.sha().equals(changes.sha()) || current.diffRefs() != null && changes.diffRefs() != null && !current.diffRefs().equals(changes.diffRefs())) {
            throw new HostingFailure(REVISION_CONFLICT, "GitLab merge request changes no longer match the current revision. Refresh and try again.");
        }
        return validateFiles(changes.changes());
    }

    private static void validateIdentity(String path, Long projectId, Long iid, String sha)
    {
        if (projectId == null || iid == null || projectId <= 0 || iid <= 0 || !path.equals("projects/" + projectId + "/merge_requests/" + iid) || sha == null || sha.isBlank()) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned incomplete or mismatched merge request metadata. Refresh and try again.");
        }
    }

    private static List<GitLabFileDiff> validateFiles(List<GitLabFileDiff> files)
    {
        if (files.stream().anyMatch(file -> file == null || file.oldPath() == null || file.oldPath().isEmpty() || file.newPath() == null || file.newPath().isEmpty())) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned an invalid changed file. Refresh and try again.");
        }
        return ImmutableList.copyOf(files);
    }

    private static void validateRefs(String sha, DiffRefs refs)
    {
        if (refs == null) {
            return;
        }
        if (refs.headSha() == null || refs.headSha().isBlank() || refs.startSha() == null || refs.startSha().isBlank() || refs.baseSha() == null || refs.baseSha().isBlank()) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned incomplete merge request diff references. Refresh and try again.");
        }
        if (!sha.equals(refs.headSha())) {
            throw new HostingFailure(REVISION_CONFLICT, "GitLab merge request diff references no longer match the current revision. Refresh and try again.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Changes(@JsonProperty("project_id") Long projectId, Long iid, String sha, @JsonProperty("diff_refs") DiffRefs diffRefs,
            List<GitLabFileDiff> changes, Boolean overflow) {}
}
