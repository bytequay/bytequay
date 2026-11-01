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
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;

/** How a GitLab release returns a merge request's changed files. */
interface GitLabDiffs
{
    /** Every changed file of the merge request at {@code mergeRequestPath}, with its patch where GitLab returns one. */
    List<GitLabFileDiff> mergeRequest(HostingAccess access, String mergeRequestPath);

    /** A comparison's files as this release reports them, a file whose text was left out marked as not shown. */
    List<GitLabFileDiff> compared(List<GitLabFileDiff> files);

    static List<GitLabFileDiff> validated(List<GitLabFileDiff> files)
    {
        if (files.stream().anyMatch(file -> file == null || file.oldPath() == null || file.oldPath().isEmpty() || file.newPath() == null || file.newPath().isEmpty())) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned an invalid changed file. Refresh and try again.");
        }
        return ImmutableList.copyOf(files);
    }
}
