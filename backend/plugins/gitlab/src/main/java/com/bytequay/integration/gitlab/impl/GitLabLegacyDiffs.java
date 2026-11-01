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

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * GitLab before 15.7 has no paged diffs: one changes read returns each file's full text up to the server's diff limits,
 * after which it stops listing files; a comparison of the same revisions still names every file.
 */
final class GitLabLegacyDiffs
        implements GitLabDiffs
{
    private final GitLabHttpClient http;

    GitLabLegacyDiffs(GitLabHttpClient http)
    {
        this.http = requireNonNull(http, "http is null");
    }

    @Override
    public List<GitLabFileDiff> mergeRequest(HostingAccess access, String path)
    {
        GitLabMergeRequestResponse current = http.get(access, path, Optional.empty(), GitLabMergeRequestResponse.class).body().orElseThrow();
        validateIdentity(path, current.projectId(), current.iid(), current.sha());
        validateRefs(current.sha(), current.diffRefs());
        // Without raw diffs GitLab empties every file's text once the request is large.
        Changes changes = http.get(access, path + "/changes?access_raw_diffs=true", Optional.empty(), Changes.class).body().orElseThrow();
        validateIdentity(path, changes.projectId(), changes.iid(), changes.sha());
        validateRefs(changes.sha(), changes.diffRefs());
        if (changes.overflow() == null || changes.changes() == null) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned incomplete merge request changes. Refresh and try again.");
        }
        if (!current.sha().equals(changes.sha()) || current.diffRefs() != null && changes.diffRefs() != null && !current.diffRefs().equals(changes.diffRefs())) {
            throw new HostingFailure(REVISION_CONFLICT, "GitLab merge request changes no longer match the current revision. Refresh and try again.");
        }
        List<GitLabFileDiff> files = compared(GitLabDiffs.validated(changes.changes()));
        // A count such as "71+" means the server stopped at its diff limits.
        if (!changes.overflow() && (changes.changesCount() == null || !changes.changesCount().endsWith("+"))) {
            return files;
        }
        return ImmutableList.<GitLabFileDiff>builder().addAll(files).addAll(remaining(access, path, changes.diffRefs(), files)).build();
    }

    @Override
    public List<GitLabFileDiff> compared(List<GitLabFileDiff> files)
    {
        // These releases have no collapsed flag: a file whose text GitLab left out arrives with an empty diff.
        return files.stream().map(file -> file.diff() == null || file.diff().isEmpty() ? notShown(file) : file).collect(toImmutableList());
    }

    /** The files past the server's diff limits, named by a comparison of the same revisions and shown without their text. */
    private List<GitLabFileDiff> remaining(HostingAccess access, String path, DiffRefs refs, List<GitLabFileDiff> listed)
    {
        if (refs == null) {
            throw new HostingFailure(UNAVAILABLE, "GitLab returned truncated merge request changes. Open the merge request in GitLab to view the complete diff.");
        }
        String project = path.substring(0, path.indexOf("/merge_requests/"));
        Compare compare = http.get(access, project + "/repository/compare?from=" + encode(refs.baseSha()) + "&to=" + encode(refs.headSha()) + "&straight=true",
                Optional.empty(), Compare.class).body().orElseThrow();
        if (compare.compareTimeout() || compare.diffs() == null) {
            throw new HostingFailure(UNAVAILABLE, "GitLab returned truncated merge request changes. Open the merge request in GitLab to view the complete diff.");
        }
        Set<String> shown = listed.stream().map(GitLabFileDiff::newPath).collect(toImmutableSet());
        return GitLabDiffs.validated(compare.diffs()).stream()
                .filter(file -> !shown.contains(file.newPath()))
                .map(GitLabLegacyDiffs::notShown)
                .collect(toImmutableList());
    }

    private static GitLabFileDiff notShown(GitLabFileDiff file)
    {
        return new GitLabFileDiff(file.oldPath(), file.newPath(), "", file.newFile(), file.renamedFile(), file.deletedFile(), true, file.tooLarge());
    }

    private static void validateIdentity(String path, Long projectId, Long iid, String sha)
    {
        if (projectId == null || iid == null || projectId <= 0 || iid <= 0 || !path.equals("projects/" + projectId + "/merge_requests/" + iid) || sha == null || sha.isBlank()) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned incomplete or mismatched merge request metadata. Refresh and try again.");
        }
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
            List<GitLabFileDiff> changes, Boolean overflow, @JsonProperty("changes_count") String changesCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Compare(List<GitLabFileDiff> diffs, @JsonProperty("compare_timeout") boolean compareTimeout) {}
}
