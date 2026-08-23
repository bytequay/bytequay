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

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Reads which merged pull request introduced each source commit.
 *
 * <p>Read-only, and never load-bearing: when GitHub is unavailable or the
 * association is ambiguous, the commit stays visible, selectable, and
 * ungrouped.
 */
@Component
public class CherryPickPullRequestLookup
{
    /** Bounded so a 300-commit range cannot open 300 simultaneous requests. */
    private static final int CONCURRENCY = 8;
    private static final int MAX_CACHED = 2000;

    private final RestClient gitHub;
    private final Supplier<String> tokenSupplier;
    /** Read from eight threads at once, so it has to be thread-safe on its own. */
    private final Cache<String, Optional<PullRequestAssociation>> cache = CacheBuilder.newBuilder()
            .maximumSize(MAX_CACHED)
            .build();

    @Autowired
    CherryPickPullRequestLookup(RestClient gitHubRestClient, CredentialService credentials)
    {
        this(gitHubRestClient, credentials::resolveGitHubToken);
    }

    CherryPickPullRequestLookup(RestClient gitHub, Supplier<String> tokenSupplier)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.tokenSupplier = requireNonNull(tokenSupplier, "tokenSupplier is null");
    }

    /** Looks up only the requested SHAs and reports provider failures separately from checked negatives. */
    public PullRequestLookupResult lookUpResult(
            RepoRef repository,
            List<String> sourceShas,
            String sourceBranch,
            List<String> warnings)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(sourceShas, "sourceShas is null");
        requireNonNull(warnings, "warnings is null");
        if (sourceShas.isEmpty()) {
            return new PullRequestLookupResult(ImmutableList.of(), Set.of());
        }
        String token;
        try {
            token = tokenSupplier.get();
        }
        catch (RuntimeException e) {
            warnings.add("Pull-request grouping is unavailable without GitHub credentials. "
                    + "Every commit is listed individually and stays selectable.");
            return new PullRequestLookupResult(ImmutableList.of(), Set.copyOf(sourceShas));
        }

        List<PullRequestLookupResult.Association> associations = new ArrayList<>();
        Set<String> failedShas = new java.util.LinkedHashSet<>();
        int ambiguous = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)) {
            List<PendingLookup> futures = new ArrayList<>(sourceShas.size());
            for (String sha : sourceShas) {
                Callable<Lookup> task = () -> new Lookup(sha, associate(repository, sha, sourceBranch, token));
                futures.add(new PendingLookup(sha, pool.submit(task)));
            }
            for (PendingLookup pending : futures) {
                try {
                    Lookup lookup = pending.future().get();
                    lookup.association().ifPresent(association -> associations.add(
                            new PullRequestLookupResult.Association(lookup.sha(), association)));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failedShas.add(pending.sha());
                }
                catch (ExecutionException e) {
                    failedShas.add(pending.sha());
                }
            }
        }
        ambiguous = sourceShas.size() - associations.size() - failedShas.size();
        if (!failedShas.isEmpty()) {
            warnings.add(failedShas.size() + " commit(s) could not be checked against GitHub. "
                    + "They are listed individually and stay selectable.");
        }
        if (ambiguous > 0) {
            warnings.add(ambiguous + " commit(s) have no single merged pull request. "
                    + "They are listed individually rather than grouped under a guess.");
        }
        return new PullRequestLookupResult(associations, failedShas);
    }

    private Optional<PullRequestAssociation> associate(
            RepoRef repository,
            String sha,
            String sourceBranch,
            String token)
    {
        String key = repository.fullName() + "@" + sha;
        Optional<PullRequestAssociation> cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Optional<PullRequestAssociation> association = choose(fetch(repository, sha, token), sourceBranch);
        cache.put(key, association);
        return association;
    }

    private List<AssociatedPullRequest> fetch(RepoRef repository, String sha, String token)
    {
        AssociatedPullRequest[] response = gitHub.get()
                .uri(uri -> uri.path("/repos/{owner}/{repo}/commits/{sha}/pulls")
                        .queryParam("per_page", 100)
                        .build(repository.owner(), repository.repo(), sha))
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(AssociatedPullRequest[].class);
        return response == null ? ImmutableList.of() : ImmutableList.copyOf(response);
    }

    /**
     * Picks the one merged pull request that introduced the commit.
     *
     * <p>Returns empty rather than guessing: an ungrouped commit is a smaller
     * problem than one filed under the wrong pull request.
     */
    private static Optional<PullRequestAssociation> choose(
            List<AssociatedPullRequest> candidates,
            String sourceBranch)
    {
        List<AssociatedPullRequest> merged = candidates.stream()
                .filter(candidate -> candidate.mergedAt() != null && candidate.base() != null)
                .collect(toImmutableList());
        if (merged.size() > 1) {
            List<AssociatedPullRequest> onSourceBranch = merged.stream()
                    .filter(candidate -> sourceBranch.equals(candidate.base().ref()))
                    .collect(toImmutableList());
            merged = onSourceBranch.size() == 1 ? onSourceBranch : merged;
        }
        if (merged.size() != 1) {
            return Optional.empty();
        }
        AssociatedPullRequest chosen = merged.getFirst();
        return Optional.of(new PullRequestAssociation(
                chosen.number(),
                chosen.title() == null ? "" : chosen.title(),
                chosen.htmlUrl() == null ? "" : chosen.htmlUrl(),
                chosen.base().ref(),
                chosen.mergedAt()));
    }

    private record Lookup(String sha, Optional<PullRequestAssociation> association)
    {
        private Lookup
        {
            requireNonNull(sha, "sha is null");
            requireNonNull(association, "association is null");
        }
    }

    private record PendingLookup(String sha, Future<Lookup> future) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AssociatedPullRequest(
            int number,
            String title,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("merged_at") String mergedAt,
            Base base)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Base(String ref) {}
    }
}
