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
package com.bytequay.app.web.cherrypick;

import com.bytequay.app.cherrypick.CherryPickCommitPreview;
import com.bytequay.app.cherrypick.CherryPickPreview;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** Preview response body. */
public record CherryPickPreviewResponse(
        String previewId,
        String repository,
        String sourceRemote,
        String targetRemote,
        String targetBranch,
        String localTargetBranch,
        String sourceBaseSha,
        String sourceTipSha,
        String targetSha,
        List<CommitResponse> commits,
        List<String> warnings,
        Instant expiresAt)
{
    public static CherryPickPreviewResponse of(CherryPickPreview preview)
    {
        return new CherryPickPreviewResponse(
                preview.previewId(),
                preview.repository().fullName(),
                preview.sourceRemote(),
                preview.targetRemote(),
                preview.targetBranch(),
                preview.localTargetBranch(),
                preview.sourceBaseSha(),
                preview.sourceTipSha(),
                preview.targetSha(),
                preview.commits().stream().map(CommitResponse::of).collect(toImmutableList()),
                ImmutableList.copyOf(preview.warnings()),
                preview.expiresAt());
    }

    /**
     * One source commit.
     *
     * @param alreadyOnTarget derived from evidence; a title or patch similarity never sets it
     * @param patchEquivalent advisory only; the commit stays selectable
     */
    public record CommitResponse(
            int position,
            String sourceSha,
            String sourceParentSha,
            String title,
            String author,
            Instant committedAt,
            List<String> changedPaths,
            boolean originallyEmpty,
            boolean alreadyOnTarget,
            Optional<String> targetMatchKind,
            Optional<String> targetMatchCommitSha,
            boolean patchEquivalent)
    {
        public static CommitResponse of(CherryPickCommitPreview commit)
        {
            return new CommitResponse(
                    commit.position(),
                    commit.sourceSha(),
                    commit.sourceParentSha(),
                    commit.title(),
                    commit.author(),
                    commit.committedAt(),
                    ImmutableList.copyOf(commit.changedPaths()),
                    commit.originallyEmpty(),
                    commit.alreadyOnTarget(),
                    commit.targetMatch().map(match -> match.kind().name()),
                    commit.targetMatch().map(match -> match.targetCommitSha()),
                    commit.patchEquivalent());
        }
    }
}
