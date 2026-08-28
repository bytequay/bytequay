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

import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.app.repository.github.ConditionalList;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubActionsClient.PrCheckRunsFetch;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Owns persisted pull-request detail and diff snapshots. */
@Service
public class PullRequestSnapshotService
{
    private static final StoredPrDiff EMPTY_DIFF = new StoredPrDiff(ImmutableList.of(), ImmutableList.of());

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubActionsClient actions;
    private final PrDetailStore details;
    private final PrDiffStore diffs;

    public PullRequestSnapshotService(
            GitHubPullRequestReadClient gitHub,
            GitHubActionsClient actions,
            PrDetailStore details,
            PrDiffStore diffs)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.details = requireNonNull(details, "details is null");
        this.diffs = requireNonNull(diffs, "diffs is null");
    }

    Optional<PullRequestDetail> findDetail(String repo, int number, long prId)
    {
        return details.find(prId)
                .map(stored -> PullRequestDetailMapper.toPullRequestDetail(
                        prId, repo, number, stored, stored.viewerCanWrite()));
    }

    void rerunFailedChecks(PullRequestRef pullRequest)
    {
        CiHeadSnapshot head = actions.inspect(new InspectCiHeadQuery(pullRequest));
        List<CiWorkflowRun> failedRuns = head.workflowRuns().stream()
                .filter(run -> run.jobs().stream().anyMatch(job -> job.actionableFailure()))
                .collect(toImmutableList());
        if (failedRuns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No failed GitHub Actions jobs are available to rerun");
        }

        int accepted = 0;
        String rejection = "GitHub did not accept the failed-job rerun";
        for (CiWorkflowRun run : failedRuns) {
            CiRerunResult result = actions.rerunFailedJobs(new RerunFailedJobsCommand(
                    pullRequest, run.id(), head.headSha(), run.attempt(), true));
            if (result.outcome() == CiRerunResult.Outcome.ACCEPTED) {
                accepted++;
            }
            else {
                rejection = result.reason();
            }
        }
        if (accepted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, rejection);
        }
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

    /** Stores what a {@link PullRequestDetailFetcher} fetch produced. */
    StoredPrDetail saveDetail(long prId, StoredPrDetail fetched, boolean viewerCanWrite)
    {
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
        PullRequestFetch fetched = gitHub.getPullRequest(pat, pullRequest, details.findEtag(prId));
        return refreshStatus(pat, pullRequest, prId, fetched);
    }

    StoredPrDetail refreshStatus(String pat, PullRequestRef pullRequest, long prId, PullRequestFetch fetched)
    {
        StoredPrDetail existing = details.find(prId)
                .orElseThrow(() -> missing("Pull request detail"));
        PrRawDetail raw = fetched.pullRequest()
                .map(summary -> existing.raw().withMergeability(summary.mergeable(), summary.mergeableState()))
                .orElse(existing.raw());
        PrCheckRunsFetch checks = raw.headSha() == null
                ? new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty())
                : actions.refreshPrCheckRuns(
                        pat,
                        pullRequest.owner(),
                        pullRequest.repo(),
                        raw.headSha(),
                        existing.checkRuns(),
                        details.findCheckRunsEtag(prId),
                        details.findCommitStatusesEtag(prId));
        StoredPrDetail stored = new StoredPrDetail(
                raw,
                existing.reviews(),
                existing.timeline(),
                checks.checkRuns(),
                existing.unavailableSections().stream()
                        .filter(section -> !"checks".equals(section))
                        .collect(ImmutableList.toImmutableList()),
                existing.viewerCanWrite());
        details.saveStatus(prId, stored);
        details.updateEtag(prId, fetched.etag());
        details.updateStatusEtags(prId, checks.checkRunsEtag(), checks.commitStatusesEtag());
        return stored;
    }

    void refreshDefaultDiff(String pat, PullRequestRef pullRequest, long prId)
    {
        StoredPrDiff stored = diffs.find(prId, null, null).orElse(EMPTY_DIFF);
        PrDiffEtags etags = diffs.findDefaultEtags(prId);
        ConditionalList<DiffFile> files = gitHub.fetchPrDiffFiles(pat, pullRequest, stored.files(), etags.files());
        ConditionalList<DiffCommit> commits = gitHub.fetchPrDiffCommits(pat, pullRequest, stored.commits(), etags.commits());
        if (files.unchanged() && commits.unchanged()) {
            return;
        }
        diffs.saveDefault(
                prId,
                new StoredPrDiff(
                        files.orElse(stored.files()),
                        commits.orElse(stored.commits()),
                        // Expanded file bodies stay valid exactly as long as the file
                        // list does; a changed list may have moved every patch.
                        files.unchanged() ? stored.fileContents() : ImmutableMap.of()),
                new PrDiffEtags(files.etag(), commits.etag()));
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

    Optional<Instant> findDetailUpdatedAt(long prId)
    {
        return details.find(prId)
                .map(StoredPrDetail::raw)
                .map(PrRawDetail::updatedAt);
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

    Optional<StoredPrDetail> patchDetail(long prId, UnaryOperator<StoredPrDetail> patcher)
    {
        requireNonNull(patcher, "patcher is null");
        Optional<StoredPrDetail> patched = details.find(prId).map(patcher);
        patched.ifPresent(detail -> details.save(prId, detail));
        return patched;
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
