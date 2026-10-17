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
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.app.repository.github.ConditionalList;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubActionsClient.PrCheckRunsFetch;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PrDetailFreshness;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/** Owns persisted pull-request detail and diff snapshots. */
@Service
public class PullRequestSnapshotService
{
    private static final StoredPrDiff EMPTY_DIFF = new StoredPrDiff(ImmutableList.of(), ImmutableList.of());

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubActionsClient actions;
    private final PrDetailStore details;
    private final PrDiffStore diffs;

    public PullRequestSnapshotService(GitHubPullRequestReadClient gitHub, GitHubActionsClient actions, PrDetailStore details, PrDiffStore diffs)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.details = requireNonNull(details, "details is null");
        this.diffs = requireNonNull(diffs, "diffs is null");
    }

    Optional<PullRequestDetail> findDetail(String repo, int number, long prId)
    {
        return details.find(prId)
                .map(stored -> PullRequestDetailMapper.toPullRequestDetail(prId, repo, number, stored, stored.viewerCanWrite()));
    }

    String readCheckLog(PullRequestRef pullRequest, long jobId)
    {
        CiHeadSnapshot head = actions.inspect(pullRequest);
        boolean failedJob = head.workflowRuns().stream()
                .flatMap(run -> run.jobs().stream())
                .anyMatch(job -> job.id() == jobId && job.actionableFailure());
        if (!failedJob) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This failed job is no longer part of the current PR checks. Refresh the pull request.");
        }
        String log = actions.readJobLog(pullRequest, jobId);
        String annotations = actions.readFailureAnnotations(pullRequest, jobId).stream()
                .map(message -> "GitHub annotation: " + message + "\n")
                .collect(joining());
        return annotations.isEmpty() ? log : annotations + "\n" + log;
    }

    void rerunFailedChecks(PullRequestRef pullRequest)
    {
        CiHeadSnapshot head = actions.inspect(pullRequest);
        List<CiWorkflowRun> failedRuns = head.workflowRuns().stream()
                .filter(run -> run.jobs().stream().anyMatch(job -> job.actionableFailure()))
                .collect(toImmutableList());
        if (failedRuns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No failed GitHub Actions jobs are available to rerun");
        }

        int accepted = 0;
        String rejection = "GitHub did not accept the failed-job rerun";
        for (CiWorkflowRun run : failedRuns) {
            CiRerunResult result = actions.rerunFailedJobs(new RerunFailedJobsCommand(pullRequest, run.id(), head.headSha(), run.attempt(), true));
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

    DiffFileContent getFileContent(long prId, String path, String baseSha, String headSha)
    {
        checkText(path, "path");
        return Optional.ofNullable(storedDiff(prId, baseSha, headSha).fileContents().get(path))
                .orElseThrow(() -> missing("Pull request file content"));
    }

    PrDetailFreshness findFreshness(long prId)
    {
        return Optional.ofNullable(details.findFreshness(prId)).orElseGet(PrDetailFreshness::unknown);
    }

    StoredPrDetail saveMetadata(long prId, PrRawDetail raw, boolean viewerCanWrite)
    {
        Optional<StoredPrDetail> previous = details.find(prId);
        StoredPrDetail existing = previous.orElseGet(() -> new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(StoredPrDetail.REVIEWS, StoredPrDetail.TIMELINE, StoredPrDetail.COMMENTS, StoredPrDetail.COMMITS,
                        StoredPrDetail.REVIEW_THREADS, StoredPrDetail.REACTIONS, StoredPrDetail.CHECKS), viewerCanWrite));
        boolean headChanged = !Objects.equals(existing.raw().headSha(), raw.headSha());
        List<String> unavailable = new ArrayList<>(existing.unavailableSections());
        boolean commitsChanged = headChanged || !Objects.equals(existing.raw().baseSha(), raw.baseSha());
        if (commitsChanged && !unavailable.contains(StoredPrDetail.COMMITS)) {
            unavailable.add(StoredPrDetail.COMMITS);
        }
        if (headChanged && !unavailable.contains(StoredPrDetail.CHECKS)) {
            unavailable.add(StoredPrDetail.CHECKS);
        }
        StoredPrDetail stored = new StoredPrDetail(raw, existing.reviews(), existing.timeline(), headChanged ? ImmutableList.of() : existing.checkRuns(),
                unavailable, viewerCanWrite);
        details.saveMetadata(prId, stored);
        return stored;
    }

    StoredPrDetail saveDiscussion(long prId, StoredPrDetail fetched, Instant sourceUpdatedAt)
    {
        details.saveDiscussion(prId, fetched, sourceUpdatedAt);
        // Partial sources are merged by the store; use the retained successful data too.
        return fetched.reviewsComplete() && fetched.timelineComplete() ? fetched : details.find(prId).orElse(fetched);
    }

    StoredPrDetail refreshChecks(String pat, PullRequestRef pullRequest, long prId)
    {
        StoredPrDetail existing = details.find(prId).orElseThrow(() -> missing("Pull request detail"));
        PrRawDetail raw = existing.raw();
        if (raw.headSha() == null || raw.headSha().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no head commit for checks");
        }
        PrCheckRunsFetch checks = actions.refreshPrCheckRuns(
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
        // Only a full refresh validates the timeline; a status response must not undo a write's invalidation.
        details.updateStatusEtags(prId, checks.checkRunsEtag(), checks.commitStatusesEtag());
        return stored;
    }

    boolean defaultDiffMatches(long prId, PrRawDetail raw)
    {
        return raw.baseSha() != null && raw.headSha() != null && diffs.find(prId, null, null)
                .filter(diff -> diff.validatedAt().isPresent() && diff.baseSha().filter(raw.baseSha()::equals).isPresent()
                        && diff.headSha().filter(raw.headSha()::equals).isPresent()).isPresent();
    }

    void refreshDefaultDiff(String pat, PullRequestRef pullRequest, long prId)
    {
        StoredPrDiff stored = diffs.find(prId, null, null).orElse(EMPTY_DIFF);
        Optional<PrRawDetail> raw = details.find(prId).map(StoredPrDetail::raw);
        if (raw.filter(value -> value.baseSha() != null && value.headSha() != null).isEmpty()) {
            PrRawDetail fetched = gitHub.fetchPrDetail(pat, pullRequest);
            if (fetched == null || fetched.baseSha() == null || fetched.headSha() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no diff commit coordinates");
            }
            raw = Optional.of(saveMetadata(prId, fetched, details.find(prId).map(StoredPrDetail::viewerCanWrite).orElse(false)).raw());
        }
        Optional<String> baseSha = raw.map(PrRawDetail::baseSha);
        Optional<String> headSha = raw.map(PrRawDetail::headSha);
        boolean sameCoordinates = baseSha.isPresent() && headSha.isPresent() && stored.baseSha().equals(baseSha) && stored.headSha().equals(headSha);
        PrDiffEtags etags = sameCoordinates ? diffs.findDefaultEtags(prId) : PrDiffEtags.none();
        ConditionalList<DiffFile> files = gitHub.fetchPrDiffFiles(pat, pullRequest, stored.files(), etags.files());
        ConditionalList<DiffCommit> commits = gitHub.fetchPrDiffCommits(pat, pullRequest, stored.commits(), etags.commits());
        if (baseSha.isPresent() && headSha.isPresent()) {
            // The default endpoints follow a moving PR. Do not label their bytes with coordinates that moved during retrieval.
            PullRequestFetch verified = gitHub.getPullRequest(pat, pullRequest, Optional.empty());
            boolean unchanged = verified.detailResponse().filter(response -> response.base() != null && response.head() != null
                    && baseSha.orElseThrow().equals(response.base().sha()) && headSha.orElseThrow().equals(response.head().sha())).isPresent();
            if (!unchanged) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pull request changed while fetching its diff; refresh again");
            }
        }
        diffs.saveDefault(prId, new StoredPrDiff(files.orElse(stored.files()), commits.orElse(stored.commits()),
                        sameCoordinates && files.unchanged() ? stored.fileContents() : ImmutableMap.of(), baseSha, headSha, Optional.of(Instant.now())),
                new PrDiffEtags(files.etag(), commits.etag()));
    }

    void refreshDiff(String pat, PullRequestRef pullRequest, long prId, String baseSha, String headSha)
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
                        ImmutableList.of(), ImmutableMap.of(), Optional.of(baseSha), Optional.of(headSha), Optional.of(Instant.now())));
    }

    void refreshFileContent(String pat, PullRequestRef pullRequest, long prId, String path, String baseSha, String headSha)
    {
        checkText(path, "path");
        validateRange(baseSha, headSha);
        StoredPrDiff stored = storedDiff(prId, baseSha, headSha);
        String contentBase = baseSha == null ? stored.baseSha().orElse(null) : baseSha;
        String contentHead = headSha == null ? stored.headSha().orElse(null) : headSha;
        if (contentBase == null || contentHead == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refresh the diff before opening file content");
        }
        DiffFileContent content = gitHub.fetchPrDiffFileContent(pat, pullRequest, path, contentBase, contentHead);
        if (baseSha == null) {
            if (!diffs.saveDefaultFileContent(prId, contentBase, contentHead, path, content)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pull request diff changed while fetching file content; refresh again");
            }
        }
        else {
            diffs.saveFileContent(prId, baseSha, headSha, path, content);
        }
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
        patched.ifPresent(detail -> details.saveCached(prId, detail));
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "base and head must be provided together");
        }
        if (baseSha != null) {
            checkText(baseSha, "base");
            checkText(headSha, "head");
        }
    }

    static ResponseStatusException missing(String name)
    {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, name + " has not been refreshed");
    }
}
