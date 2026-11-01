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
import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** GitLab 15.7 and later page a merge request's diffs, so no instance limit cuts a large one short. */
final class GitLabPagedDiffs
        implements GitLabDiffs
{
    private final GitLabHttpClient http;

    GitLabPagedDiffs(GitLabHttpClient http)
    {
        this.http = requireNonNull(http, "http is null");
    }

    @Override
    public List<GitLabFileDiff> mergeRequest(HostingAccess access, String mergeRequestPath)
    {
        ImmutableList.Builder<GitLabFileDiff> files = ImmutableList.builder();
        for (int page = 1; ; page++) {
            GitLabHttpClient.Response<GitLabFileDiff[]> response = http.get(access, mergeRequestPath + "/diffs?per_page=100&page=" + page, Optional.empty(),
                    GitLabFileDiff[].class);
            files.addAll(GitLabDiffs.validated(Arrays.asList(response.body().orElseThrow())));
            if (!response.hasNext()) {
                return files.build();
            }
        }
    }

    @Override
    public List<GitLabFileDiff> compared(List<GitLabFileDiff> files)
    {
        return files;
    }
}
