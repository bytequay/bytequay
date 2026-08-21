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
package com.bytequay.app.workspace;

import com.bytequay.core.domain.RepoRef;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** GitHub repository data needed to select a repository for watch preview. */
public record RepositorySearchResult(
        long id,
        RepoRef repository,
        String fullName,
        Optional<String> description,
        String visibility,
        boolean fork,
        String defaultBranch,
        String cloneUrl,
        Optional<RepoRef> upstream,
        boolean viewerCanPush,
        Optional<String> existingWorkspaceId)
{
    public RepositorySearchResult
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(fullName, "fullName is null");
        requireNonNull(description, "description is null");
        requireNonNull(visibility, "visibility is null");
        requireNonNull(defaultBranch, "defaultBranch is null");
        requireNonNull(cloneUrl, "cloneUrl is null");
        requireNonNull(upstream, "upstream is null");
        requireNonNull(existingWorkspaceId, "existingWorkspaceId is null");
    }
}
