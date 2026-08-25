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
package com.bytequay.app.service;

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.ContributionCalendar;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RecentEvent;
import com.bytequay.core.domain.RepoMeta;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredRepoMeta;
import com.bytequay.core.domain.UserCommitSummary;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.RepoMetaStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class RepoService
{
    private static final Logger log = LoggerFactory.getLogger(RepoService.class);
    private static final Duration REPO_META_TTL = Duration.ofHours(1);
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final GitHubPullRequestReadClient pullRequests;
    private final GitHubRepoClient repos;
    private final GitHubAccountClient accounts;
    private final PrViewStateStore viewStateStore;
    private final RepoMetaStore repoMetaStore;
    private final CredentialService credentialService;
    private final Executor ioExecutor;

    @Autowired
    public RepoService(
            GitHubPullRequestReadClient pullRequests,
            GitHubRepoClient repos,
            GitHubAccountClient accounts,
            PrViewStateStore viewStateStore,
            RepoMetaStore repoMetaStore,
            CredentialService credentialService,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.repos = requireNonNull(repos, "repos is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.repoMetaStore = requireNonNull(repoMetaStore, "repoMetaStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    public ContributionCalendar getContributionCalendar(String login)
    {
        requireNotBlank(login, "login must not be blank");
        try {
            return accounts.fetchContributionCalendar(credentialService.resolveGitHubToken(), login);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Contribution graph temporarily unavailable",
                    e);
        }
    }

    public List<UserCommitSummary> getUserCommitsOnDate(String login, String isoDate)
    {
        requireNotBlank(login, "login must not be blank");
        if (isoDate == null || !ISO_DATE.matcher(isoDate).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be yyyy-MM-dd");
        }
        return accounts.fetchUserCommitsOnDate(credentialService.resolveGitHubToken(), login, isoDate);
    }

    public UserProfile getUserProfile()
    {
        return credentialService.resolveGitHubCredential().profile();
    }

    public PullRequest getRepoPullRequest(String owner, String repo, int number)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequest fresh = pullRequests.getPullRequest(pat, PullRequestRef.of(owner, repo, number));
        return overlayViewState(
                fresh,
                viewStateStore.findByPrIds(ImmutableSet.of(fresh.id())).get(fresh.id()));
    }

    public RepoMeta getRepoMeta(String owner, String repo)
    {
        Optional<StoredRepoMeta> stored = repoMetaStore.find(owner, repo);
        Instant now = Instant.now();
        if (stored.isPresent()) {
            StoredRepoMeta value = stored.orElseThrow();
            boolean complete = value.meta().ownerAvatarUrl() != null;
            boolean fresh = !value.syncedAt().isBefore(now.minus(REPO_META_TTL));
            if (complete && fresh) {
                return value.meta();
            }
            if (complete) {
                ioExecutor.execute(() -> refreshRepoMeta(owner, repo));
                return value.meta();
            }
        }
        RepoMeta fresh = fetchRepoMeta(owner, repo);
        repoMetaStore.save(owner, repo, fresh, now);
        return fresh;
    }

    private RepoMeta fetchRepoMeta(String owner, String repo)
    {
        return repos.fetchRepoMeta(credentialService.resolveGitHubToken(), RepoRef.of(owner, repo));
    }

    private void refreshRepoMeta(String owner, String repo)
    {
        try {
            repoMetaStore.save(owner, repo, fetchRepoMeta(owner, repo), Instant.now());
        }
        catch (RuntimeException e) {
            log.warn("Background repo-meta refresh for {}/{} failed: {}", owner, repo, e.getMessage());
        }
    }

    public List<RecentEvent> getRecentEvents(String login)
    {
        requireNotBlank(login, "login must not be blank");
        Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC)
                .toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return accounts.fetchUserEvents(credentialService.resolveGitHubToken(), login, 100).stream()
                .filter(event -> event.createdAt() != null && !event.createdAt().isBefore(monthStart))
                .collect(toImmutableList());
    }

    public List<RecentEvent> getFollowingEvents(String login)
    {
        requireNotBlank(login, "login must not be blank");
        List<RecentEvent> all = accounts.fetchReceivedEvents(credentialService.resolveGitHubToken(), login, 30);
        return ImmutableList.copyOf(all.size() <= 10 ? all : all.subList(0, 10));
    }

    private static PullRequest overlayViewState(PullRequest pr, PrViewState state)
    {
        if (state == null) {
            return pr;
        }
        return new PullRequest(
                pr.id(), pr.repo(), pr.number(), pr.title(), pr.author(), pr.htmlUrl(),
                pr.createdAt(), pr.updatedAt(), pr.origin(), pr.labels(), pr.labelColors(), pr.draft(),
                state.viewedAt(), state.reviewedAt(), state.handledAction(), pr.requestedReviewers(),
                pr.ciStatus(), pr.additions(), pr.deletions(), pr.commentCount(), pr.attentionReason(),
                pr.state(), pr.closedAt(), pr.mergedAt(), pr.mergeable(), pr.mergeableState(),
                pr.headPushedAt(), pr.reviewerVerdicts(), pr.headRef());
    }
}
