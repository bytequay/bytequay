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
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.ContributionCalendar;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RecentEvent;
import com.bytequay.core.domain.UserCommitSummary;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class RepoService
{
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final GitHubPullRequestReadClient pullRequests;
    private final GitHubAccountClient accounts;
    private final PrViewStateStore viewStateStore;
    private final PullRequestStore pullRequestStore;
    private final CredentialService credentialService;

    public RepoService(
            GitHubPullRequestReadClient pullRequests,
            PullRequestStore pullRequestStore,
            GitHubAccountClient accounts,
            PrViewStateStore viewStateStore,
            CredentialService credentialService)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
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
        PullRequestRef ref = PullRequestRef.of(owner, repo, number);
        // Storing it first is what turns a fetched pull request into one this
        // application can work with: only a stored row carries an id, and view
        // state hangs off that id.
        PullRequest stored = pullRequestStore.saveObserved(pullRequests.getPullRequest(pat, ref));
        return stored.withViewState(viewStateStore.findByPrIds(ImmutableSet.of(stored.id())).get(stored.id()));
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
}
