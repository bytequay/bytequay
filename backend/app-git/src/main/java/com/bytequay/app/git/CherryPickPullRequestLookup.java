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
package com.bytequay.app.git;

import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestAssociation;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Reads which merged pull request introduced each source commit.
 *
 * <p>Read-only, and never load-bearing: when the provider is unavailable or the
 * association is ambiguous, the commit stays visible, selectable, and
 * ungrouped.
 */
@Component
public class CherryPickPullRequestLookup
{
    /** Bounded so a 300-commit range cannot open 300 simultaneous requests. */
    private static final int CONCURRENCY = 8;
    private static final int MAX_CACHED = 2000;

    private final HostingIntegrations integrations;
    /** Read from eight threads at once, so it has to be thread-safe on its own. */
    private final Cache<CacheKey, PullRequestAssociation> cache = CacheBuilder.newBuilder()
            .maximumSize(MAX_CACHED)
            .build();

    public CherryPickPullRequestLookup(HostingIntegrations integrations)
    {
        this.integrations = requireNonNull(integrations, "integrations is null");
    }

    /** Looks up only the requested SHAs in the target repository and reports provider failures separately from checked negatives. */
    public PullRequestLookupResult lookUpResult(HostingTarget target, List<String> sourceShas, String sourceBranch, List<String> warnings)
    {
        requireNonNull(target, "target is null");
        requireNonNull(sourceShas, "sourceShas is null");
        requireNonNull(sourceBranch, "sourceBranch is null");
        requireNonNull(warnings, "warnings is null");
        if (sourceShas.isEmpty()) {
            return new PullRequestLookupResult(ImmutableList.of(), ImmutableSet.of());
        }
        PullRequestContentReader contents = integrations.of(target).contents();
        List<PullRequestLookupResult.Association> associations = new ArrayList<>();
        Set<String> failedShas = new LinkedHashSet<>();
        // After a rate limit every further call fails too; the rest count as failed and stay due.
        AtomicBoolean rateLimited = new AtomicBoolean();
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)) {
            List<PendingLookup> futures = new ArrayList<>(sourceShas.size());
            for (String sha : sourceShas) {
                Callable<Lookup> task = () -> {
                    checkState(!rateLimited.get(), "skipped after a rate limit");
                    try {
                        return new Lookup(sha, associate(contents, target, sha, sourceBranch));
                    }
                    catch (RuntimeException failure) {
                        if (isRateLimit(failure)) {
                            rateLimited.set(true);
                        }
                        throw failure;
                    }
                };
                futures.add(new PendingLookup(sha, pool.submit(task)));
            }
            for (PendingLookup pending : futures) {
                try {
                    Lookup lookup = pending.future().get();
                    lookup.association().ifPresent(association -> associations.add(new PullRequestLookupResult.Association(lookup.sha(), association)));
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
        int ambiguous = sourceShas.size() - associations.size() - failedShas.size();
        if (!failedShas.isEmpty()) {
            warnings.add(failedShas.size() + " commit(s) could not be checked: responses were unavailable or incomplete. " + "They are listed individually and stay selectable.");
        }
        if (ambiguous > 0) {
            warnings.add(ambiguous + " commit(s) have no single merged pull request. " + "They are listed individually rather than grouped under a guess.");
        }
        return new PullRequestLookupResult(associations, failedShas);
    }

    private Optional<PullRequestAssociation> associate(PullRequestContentReader contents, HostingTarget target, String sha, String sourceBranch)
    {
        CacheKey key = new CacheKey(target.repository().id(), sha, sourceBranch);
        Optional<PullRequestAssociation> cached = Optional.ofNullable(cache.getIfPresent(key));
        if (cached.isPresent()) {
            return cached;
        }
        Optional<PullRequestAssociation> association = choose(contents.mergedWithCommit(target, sha), sourceBranch);
        // Durable enrichment owns negative retry timing; memoizing a miss here would suppress a due retry.
        association.ifPresent(value -> cache.put(key, value));
        return association;
    }

    /**
     * Picks the one merged pull request that introduced the commit.
     *
     * <p>Returns empty rather than guessing: an ungrouped commit is a smaller
     * problem than one filed under the wrong pull request.
     */
    private static Optional<PullRequestAssociation> choose(List<PullRequestAssociation> merged, String sourceBranch)
    {
        if (merged.size() > 1) {
            List<PullRequestAssociation> onSourceBranch = merged.stream()
                    .filter(candidate -> sourceBranch.equals(candidate.baseRef()))
                    .collect(toImmutableList());
            merged = onSourceBranch.size() == 1 ? onSourceBranch : merged;
        }
        return merged.size() == 1 ? Optional.of(merged.getFirst()) : Optional.empty();
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

    /** Keyed by the retained hosting repository, which is unique across servers. */
    private record CacheKey(long repositoryId, String sha, String sourceBranch) {}
}
