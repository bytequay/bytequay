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
package com.bytequay.app.service.hosting;

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.actions.CiHeadSnapshot;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.GitHubPullRequestIdentity;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestCheckLog;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.hosting.DiscussionProbe;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.getDone;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.submit;
import static com.google.common.util.concurrent.Futures.whenAllComplete;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.joining;

/**
 * Fetches pull-request discussion sources concurrently on the IO executor,
 * preserving the metadata and checks refreshed separately by the caller.
 */
@Component
public class GitHubPullRequestContentReader
        implements PullRequestContentReader
{
    private static final Pattern ACTIONS_JOB = Pattern.compile("^/[^/]+/[^/]+/actions/runs/[0-9]+/job/([0-9]+)/?$");
    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestContentReader.class);

    private final GitHubPullRequestReadClient gitHub;
    private final Executor ioExecutor;
    private final GitHubActionsClient actions;
    private final GitHubAccountClient accounts;

    public GitHubPullRequestContentReader(GitHubPullRequestReadClient gitHub, GitHubActionsClient actions, GitHubAccountClient accounts,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    @Override
    public HostingProvider provider()
    {
        return HostingProvider.GITHUB;
    }

    @Override
    public String headRef(int number)
    {
        checkArgument(number > 0, "number must be positive");
        return "refs/pull/" + number + "/head";
    }

    @Override
    public GitHubPullRequestIdentity identity(HostingAccess access, HostingRepository repository, int number)
    {
        return gitHub.fetchPullRequestIdentity(access.token(), ref(access, repository, number));
    }

    @Override
    public List<GitHubPullRequestIdentity> identitiesByHead(HostingAccess access, HostingRepository repository, RepoRef sourceRepository, String branch)
    {
        PullRequestRef ref = ref(access, repository, 1);
        return gitHub.fetchPullRequestIdentities(access.token(), RepoRef.of(ref.owner(), ref.repo()), sourceRepository, branch);
    }

    @Override
    public boolean viewerCanWrite(HostingAccess access, HostingRepository repository)
    {
        PullRequestRef ref = ref(access, repository, 1);
        return accounts.fetchViewerCanWrite(access.token(), RepoRef.of(ref.owner(), ref.repo()));
    }

    @Override
    public Optional<DiscussionProbe> probeDiscussion(HostingAccess access, HostingRepository repository, int number, Map<String, String> etags)
    {
        return Optional.of(gitHub.probeDiscussion(access.token(), ref(access, repository, number), etags));
    }

    @Override
    public StoredPrDetail discussion(HostingAccess access, HostingRepository repository, int number, StoredPrDetail existing)
    {
        return await(fetchDiscussion(access.token(), ref(access, repository, number), existing));
    }

    @Override
    public Checks checks(HostingAccess access, HostingRepository repository, int number, String headSha,
            List<PrCheckRunState> existing, Optional<String> checksEtag, Optional<String> statusesEtag)
    {
        PullRequestRef ref = ref(access, repository, number);
        List<PrCheckRunState> cached = existing.stream()
                .map(check -> "job".equals(check.source()) ? withSource(check, check.githubId(), "check_run") : check).collect(toImmutableList());
        var result = actions.refreshPrCheckRuns(access.token(), ref.owner(), ref.repo(), headSha, cached, checksEtag, statusesEtag);
        return new Checks(result.checkRuns().stream().map(GitHubPullRequestContentReader::normalizeJob).collect(toImmutableList()),
                result.checkRunsEtag(), result.commitStatusesEtag(), true);
    }

    private static PrCheckRunState normalizeJob(PrCheckRunState check)
    {
        if (!"check_run".equals(check.source()) || check.detailsUrl() == null) {
            return check;
        }
        try {
            URI url = URI.create(check.detailsUrl());
            Matcher job = ACTIONS_JOB.matcher(url.getPath());
            if ("https".equals(url.getScheme()) && "github.com".equals(url.getHost()) && job.matches()) {
                return withSource(check, Long.parseLong(job.group(1)), "job");
            }
        }
        catch (IllegalArgumentException ignored) {
            return check;
        }
        return check;
    }

    private static PrCheckRunState withSource(PrCheckRunState check, Long id, String source)
    {
        return new PrCheckRunState(id, check.name(), check.status(), check.conclusion(), check.detailsUrl(), source, check.startedAt(), check.completedAt());
    }

    @Override
    public Diff diff(HostingAccess access, HostingRepository repository, int number, Optional<String> baseSha, Optional<String> headSha,
            StoredPrDiff existing, PrDiffEtags etags)
    {
        PullRequestRef ref = ref(access, repository, number);
        if (baseSha.isPresent()) {
            return new Diff(gitHub.fetchPrDiffFiles(access.token(), ref, baseSha.orElseThrow(), headSha.orElseThrow()), ImmutableList.of(), PrDiffEtags.none());
        }
        var files = gitHub.fetchPrDiffFiles(access.token(), ref, existing.files(), etags.files());
        var commits = gitHub.fetchPrDiffCommits(access.token(), ref, existing.commits(), etags.commits());
        return new Diff(files.orElse(existing.files()), commits.orElse(existing.commits()), new PrDiffEtags(files.etag(), commits.etag()));
    }

    @Override
    public PullRequestCheckLog readCheckLog(HostingAccess access, HostingRepository repository, int number, long jobId)
    {
        PullRequestRef pullRequest = ref(access, repository, number);
        CiHeadSnapshot head = actions.inspect(access.token(), pullRequest);
        boolean failedJob = head.workflowRuns().stream()
                .flatMap(run -> run.jobs().stream())
                .anyMatch(job -> job.id() == jobId && job.actionableFailure());
        if (!failedJob) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This failed job is no longer part of the current PR checks. Refresh the pull request.");
        }
        String annotations = actions.readFailureAnnotations(access.token(), pullRequest, jobId).stream()
                .map(message -> "GitHub annotation: " + message + "\n")
                .collect(joining());
        try {
            String log = actions.readJobLog(access.token(), pullRequest, jobId);
            return new PullRequestCheckLog(annotations.isEmpty() ? log : annotations + "\n" + log, Optional.empty());
        }
        catch (ResponseStatusException failure) {
            return new PullRequestCheckLog(annotations, Optional.ofNullable(failure.getReason()).or(() -> Optional.of("GitHub Actions log retrieval failed")));
        }
    }

    @Override
    public DiffFileContent fileContent(HostingAccess access, HostingRepository repository, int number, String path, String baseSha, String headSha, boolean fullDiff)
    {
        return gitHub.fetchPrDiffFileContent(access.token(), ref(access, repository, number), path, baseSha, headSha);
    }

    private static PullRequestRef ref(HostingAccess access, HostingRepository repository, int number)
    {
        checkArgument(access.server().equals(GITHUB_SERVER), "This operation requires a GitHub.com account");
        checkArgument(repository.instanceId() == access.instanceId().orElseThrow(), "Repository belongs to another hosting instance");
        return parseRef(repository.fullPath(), number);
    }

    ListenableFuture<StoredPrDetail> fetchDiscussion(String pat, PullRequestRef ref, StoredPrDetail existing)
    {
        PrRawDetail raw = existing.raw();
        ListenableFuture<SourceItems<PrReviewState>> reviews = fetchOptionalListAsync("fetchPrReviews", StoredPrDetail.REVIEWS, ref, () -> gitHub.fetchPrReviews(pat, ref));
        ListenableFuture<SourceItems<PrTimelineEvent>> timeline = fetchOptionalListAsync("fetchPrTimeline", StoredPrDetail.TIMELINE, ref, () -> gitHub.fetchPrTimeline(pat, ref));
        ListenableFuture<SourceItems<PrTimelineEvent>> comments = fetchOptionalListAsync(
                "fetchPrIssueComments", StoredPrDetail.COMMENTS, ref, () -> gitHub.fetchPrIssueComments(pat, ref));
        // ponytail: Initial hydration also fetches diff commits for their parent SHAs; subsequent discussions reuse these immutable events.
        ListenableFuture<SourceItems<PrTimelineEvent>> commits = !existing.unavailableSections().contains(StoredPrDetail.COMMITS)
                ? immediateFuture(new SourceItems<>(existing.timeline().stream().filter(event -> "committed".equals(event.event())).collect(toImmutableList()), null))
                : fetchOptionalListAsync("fetchPrCommits", StoredPrDetail.COMMITS, ref, () -> gitHub.fetchPrCommits(pat, ref));
        ListenableFuture<SourceItems<PrTimelineEvent>> threads = fetchOptionalListAsync(
                "fetchPrReviewThreads", StoredPrDetail.REVIEW_THREADS, ref, () -> gitHub.fetchPrReviewThreads(pat, ref));
        ListenableFuture<SourceItems<PrReaction>> descriptionReactions = fetchOptionalListAsync(
                "fetchPrDescriptionReactions", StoredPrDetail.REACTIONS, ref, () -> gitHub.fetchPrDescriptionReactions(pat, ref));
        ListenableFuture<SourceItems<PrCheckRunState>> checks = immediateFuture(
                new SourceItems<>(existing.checkRuns(), existing.checkRunsComplete() ? null : StoredPrDetail.CHECKS));

        // Not whenAllSucceed: during a rate-limit pause every source fails alike, and it logs each later failure as an error with a stack trace.
        return whenAllComplete(reviews, timeline, comments, commits, threads, descriptionReactions, checks)
                .call(() -> new StoredPrDetail(
                                raw,
                                getDone(reviews).items(),
                                mergeTimeline(
                                        getDone(timeline).items(),
                                        getDone(comments).items(),
                                        getDone(commits).items(),
                                        getDone(threads).items(),
                                        getDone(descriptionReactions).items()),
                                getDone(checks).items(),
                                unavailable(reviews, timeline, comments, commits, threads, descriptionReactions, checks),
                                existing.viewerCanWrite()),
                        directExecutor());
    }

    // Unwrap so a ResponseStatusException from a fetch keeps its HTTP status
    // instead of surfacing as UncheckedExecutionException and mapping to 500.
    static <T> T await(ListenableFuture<T> future)
    {
        try {
            return future.get();
        }
        catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatusCode.valueOf(503), "GitHub PR discussion refresh was interrupted");
        }
        catch (ExecutionException e) {
            throwIfUnchecked(e.getCause());
            throw new UncheckedExecutionException(e.getCause());
        }
    }

    private <T> ListenableFuture<SourceItems<T>> fetchOptionalListAsync(String name, String section, PullRequestRef ref, Supplier<List<T>> task)
    {
        return submit(() -> {
            try {
                return new SourceItems<>(requireNonNullElse(task.get(), ImmutableList.of()), null);
            }
            catch (RuntimeException e) {
                if (isRateLimit(e)) {
                    throw e;
                }
                log.warn("{}({}#{}) unavailable: {}", name, ref.repoFullName(), ref.number(), e.toString());
                return new SourceItems<>(ImmutableList.of(), section);
            }
        }, ioExecutor);
    }

    /**
     * The timeline and issue-comment endpoints both report issue comments;
     * where both returned the same comment, keep the issue-comment copy.
     */
    private static List<PrTimelineEvent> mergeTimeline(
            List<PrTimelineEvent> timeline,
            List<PrTimelineEvent> comments,
            List<PrTimelineEvent> commits,
            List<PrTimelineEvent> threads,
            List<PrReaction> descriptionReactions)
    {
        Set<String> commentKeys = comments.stream()
                .map(GitHubPullRequestContentReader::commentKey)
                .filter(Objects::nonNull)
                .collect(toImmutableSet());
        List<PrTimelineEvent> base = Stream.concat(
                        timeline.stream().filter(event -> !"commented".equals(event.event()) || !commentKeys.contains(commentKey(event))),
                        comments.stream())
                .collect(toImmutableList());
        Set<String> commitShas = base.stream()
                .map(event -> event.payload().commitSha())
                .filter(Objects::nonNull)
                .collect(toImmutableSet());
        Stream<PrTimelineEvent> events = Stream.of(base.stream(), commits.stream().filter(event -> !commitShas.contains(event.payload().commitSha())), threads.stream())
                .flatMap(stream -> stream);
        if (!descriptionReactions.isEmpty()) {
            events = Stream.concat(events, Stream.of(new PrTimelineEvent(
                    null,
                    "description_reactions",
                    null,
                    null,
                    null,
                    PrTimelinePayload.builder().reactions(descriptionReactions).build(),
                    java.util.Optional.empty())));
        }
        return events
                .collect(toImmutableList());
    }

    @SafeVarargs
    private static List<String> unavailable(ListenableFuture<? extends SourceItems<?>>... futures)
            throws Exception
    {
        List<String> sections = new ArrayList<>();
        for (ListenableFuture<? extends SourceItems<?>> future : futures) {
            String section = getDone(future).unavailableSection();
            if (section != null) {
                sections.add(section);
            }
        }
        return ImmutableList.copyOf(sections);
    }

    private static String commentKey(PrTimelineEvent event)
    {
        return event.actor() == null || event.timestamp() == null ? null : event.actor() + "@" + event.timestamp();
    }

    private record SourceItems<T>(List<T> items, String unavailableSection) {}
}
