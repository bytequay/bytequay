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
package com.bytequay.app.service.pr;

import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static java.util.Objects.requireNonNull;

/** Owns persisted pull-request detail and diff snapshots. */
@Service
public class PullRequestSnapshotService
{
    private final GitHubPullRequestReadClient gitHub;
    private final GitHubActionsClient actions;
    private final PrDetailStore details;
    private final PrDiffStore diffs;
    private final PullRequestDetailFetcher detailFetcher;

    public PullRequestSnapshotService(
            GitHubPullRequestReadClient gitHub,
            GitHubActionsClient actions,
            PrDetailStore details,
            PrDiffStore diffs,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.details = requireNonNull(details, "details is null");
        this.diffs = requireNonNull(diffs, "diffs is null");
        this.detailFetcher = new PullRequestDetailFetcher(
                gitHub,
                actions,
                details,
                requireNonNull(ioExecutor, "ioExecutor is null"));
    }

    Optional<PullRequestDetail> findDetail(String repo, int number, long prId)
    {
        return details.find(prId)
                .map(stored -> PullRequestDetailMapper.toPullRequestDetail(
                        repo, number, stored, stored.viewerCanWrite()));
    }

    List<DiffFile> getDiffFiles(long prId, String baseSha, String headSha)
    {
        return storedDiff(prId, baseSha, headSha).files();
    }

    List<DiffCommit> getDiffCommits(long prId)
    {
        return storedDiff(prId, null, null).commits();
    }

    DiffFileContent getFileContent(
            long prId,
            String path,
            String baseSha,
            String headSha)
    {
        requireNotBlank(path, "path");
        return Optional.ofNullable(storedDiff(prId, baseSha, headSha).fileContents().get(path))
                .orElseThrow(() -> missing("Pull request file content"));
    }

    StoredPrDetail refreshDetail(
            String pat,
            PullRequestRef pullRequest,
            long prId,
            boolean viewerCanWrite)
    {
        StoredPrDetail fetched = detailFetcher.fetch(pat, pullRequest);
        StoredPrDetail stored = new StoredPrDetail(
                fetched.raw(),
                fetched.reviews(),
                fetched.timeline(),
                fetched.checkRuns(),
                fetched.unavailableSections(),
                viewerCanWrite);
        details.save(prId, stored);
        return stored;
    }

    StoredPrDetail refreshStatus(String pat, PullRequestRef pullRequest, long prId)
    {
        StoredPrDetail existing = details.find(prId)
                .orElseThrow(() -> missing("Pull request detail"));
        PullRequestFetch fetched = gitHub.getPullRequest(pat, pullRequest, details.findEtag(prId));
        PrRawDetail raw = fetched.pullRequest()
                .map(summary -> existing.raw().withMergeability(summary.mergeable(), summary.mergeableState()))
                .orElse(existing.raw());
        List<PrCheckRunState> checks = raw.headSha() == null
                ? ImmutableList.of()
                : actions.fetchPrCheckRuns(pat, pullRequest.owner(), pullRequest.repo(), raw.headSha());
        StoredPrDetail stored = new StoredPrDetail(
                raw,
                existing.reviews(),
                existing.timeline(),
                checks == null ? ImmutableList.of() : checks,
                existing.unavailableSections().stream()
                        .filter(section -> !"checks".equals(section))
                        .collect(ImmutableList.toImmutableList()),
                existing.viewerCanWrite());
        details.saveStatus(prId, stored);
        details.updateEtag(prId, fetched.etag());
        return stored;
    }

    void refreshDefaultDiff(String pat, PullRequestRef pullRequest, long prId)
    {
        diffs.save(
                prId,
                null,
                null,
                new StoredPrDiff(
                        gitHub.fetchPrDiffFiles(pat, pullRequest),
                        gitHub.fetchPrDiffCommits(pat, pullRequest)));
    }

    void refreshDiff(
            String pat,
            PullRequestRef pullRequest,
            long prId,
            String baseSha,
            String headSha)
    {
        validateRange(baseSha, headSha);
        if (baseSha == null) {
            refreshDefaultDiff(pat, pullRequest, prId);
            return;
        }
        diffs.save(
                prId,
                baseSha,
                headSha,
                new StoredPrDiff(
                        gitHub.fetchPrDiffFiles(pat, pullRequest, baseSha, headSha),
                        ImmutableList.of()));
    }

    void refreshFileContent(
            String pat,
            PullRequestRef pullRequest,
            long prId,
            String path,
            String baseSha,
            String headSha)
    {
        requireNotBlank(path, "path");
        validateRange(baseSha, headSha);
        DiffFileContent content = baseSha == null
                ? gitHub.fetchPrDiffFileContent(pat, pullRequest, path)
                : gitHub.fetchPrDiffFileContent(
                        pat, pullRequest, path, baseSha, headSha);
        diffs.saveFileContent(prId, baseSha, headSha, path, content);
    }

    Optional<Instant> findDetailSyncedAt(String repo, int number)
    {
        return details.findSyncedAt(repo, number);
    }

    Optional<String> findDetailEtag(long prId)
    {
        return details.findEtag(prId);
    }

    void updateDetailEtag(long prId, Optional<String> etag)
    {
        details.updateEtag(prId, etag);
    }

    Optional<String> findHeadSha(long prId)
    {
        return details.find(prId)
                .map(StoredPrDetail::raw)
                .map(raw -> raw.headSha());
    }

    void patchDetail(long prId, UnaryOperator<StoredPrDetail> patcher)
    {
        requireNonNull(patcher, "patcher is null");
        details.find(prId).map(patcher).ifPresent(detail -> details.save(prId, detail));
    }

    void delete(Set<Long> prIds)
    {
        if (!prIds.isEmpty()) {
            details.deleteByPrIds(prIds);
            diffs.deleteByPrIds(prIds);
        }
    }

    void delete(long prId)
    {
        delete(ImmutableSet.of(prId));
    }

    private StoredPrDiff storedDiff(long prId, String baseSha, String headSha)
    {
        validateRange(baseSha, headSha);
        return diffs.find(prId, baseSha, headSha)
                .orElseThrow(() -> missing("Pull request diff"));
    }

    private static void validateRange(String baseSha, String headSha)
    {
        if ((baseSha == null) != (headSha == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "base and head must be provided together");
        }
        if (baseSha != null) {
            requireNotBlank(baseSha, "base");
            requireNotBlank(headSha, "head");
        }
    }

    private static ResponseStatusException missing(String name)
    {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, name + " has not been refreshed");
    }
}
