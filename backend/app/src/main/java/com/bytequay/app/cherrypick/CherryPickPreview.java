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
package com.bytequay.app.cherrypick;

import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One frozen, read-only view of a source range.
 *
 * <p>Previews are never persisted. A restart or an expiry means "preview
 * again", which is what keeps an unconfirmed plan from outliving the Git state
 * it was computed from.
 */
public record CherryPickPreview(
        String previewId,
        Optional<String> workspaceId,
        RepoRef repository,
        String sourceRemote,
        String targetRemote,
        String targetBranch,
        String localTargetBranch,
        String sourceBaseSha,
        String sourceTipSha,
        String targetSha,
        List<CherryPickCommitPreview> commits,
        List<String> warnings,
        Instant createdAt,
        Instant expiresAt)
{
    public CherryPickPreview
    {
        requireNonNull(previewId, "previewId is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(sourceRemote, "sourceRemote is null");
        requireNonNull(targetRemote, "targetRemote is null");
        requireNonNull(targetBranch, "targetBranch is null");
        requireNonNull(localTargetBranch, "localTargetBranch is null");
        requireNonNull(sourceBaseSha, "sourceBaseSha is null");
        requireNonNull(sourceTipSha, "sourceTipSha is null");
        requireNonNull(targetSha, "targetSha is null");
        commits = ImmutableList.copyOf(requireNonNull(commits, "commits is null"));
        warnings = ImmutableList.copyOf(requireNonNull(warnings, "warnings is null"));
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(expiresAt, "expiresAt is null");
    }
}
