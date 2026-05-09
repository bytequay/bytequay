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

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoActivityItem;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory TTL cache for the four repo-page list endpoints
 * ({@code /pulls}, {@code /issues}, {@code /meta}, {@code /activity}).
 * Each kind keeps its own {@code Map<RepoRef, CachedValue<T>>} with a
 * dedicated TTL — short for activity (it's a "what's happening now"
 * feed), long for meta (description / license / topics rarely change).
 *
 * <p>The pulls cache holds the raw GitHub-derived list. The viewState
 * overlay (handled / snoozed / viewed flags) is applied by
 * {@code RepoService} on every read so a click that flips local state
 * shows up immediately, without waiting for the TTL.
 */
@Component
public class RepoListCache
{
    private static final Duration PULLS_TTL = Duration.ofMinutes(2);
    private static final Duration ISSUES_TTL = Duration.ofMinutes(5);
    private static final Duration META_TTL = Duration.ofMinutes(30);
    private static final Duration ACTIVITY_TTL = Duration.ofMinutes(1);

    private final ConcurrentHashMap<RepoRef, CachedValue<List<PullRequest>>> pulls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RepoRef, CachedValue<List<RepoIssue>>> issues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RepoRef, CachedValue<RepoMeta>> meta = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RepoRef, CachedValue<List<RepoActivityItem>>> activity = new ConcurrentHashMap<>();

    public List<PullRequest> getPulls(RepoRef ref, Supplier<List<PullRequest>> loader)
    {
        return getOrLoad(pulls, ref, loader, PULLS_TTL);
    }

    public List<RepoIssue> getIssues(RepoRef ref, Supplier<List<RepoIssue>> loader)
    {
        return getOrLoad(issues, ref, loader, ISSUES_TTL);
    }

    public RepoMeta getMeta(RepoRef ref, Supplier<RepoMeta> loader)
    {
        return getOrLoad(meta, ref, loader, META_TTL);
    }

    public List<RepoActivityItem> getActivity(RepoRef ref, Supplier<List<RepoActivityItem>> loader)
    {
        return getOrLoad(activity, ref, loader, ACTIVITY_TTL);
    }

    /**
     * Drops the cached PR list for one repo. Called from PullRequestService
     * after any structural mutation that would change the list shape
     * (approve, merge, set-draft, request-reviewer, close-via-comment).
     */
    public void invalidatePulls(RepoRef ref)
    {
        pulls.remove(ref);
    }

    /** Test seam — clears every entry. Not used by production code. */
    void clearAll()
    {
        pulls.clear();
        issues.clear();
        meta.clear();
        activity.clear();
    }

    private static <T> T getOrLoad(
            ConcurrentHashMap<RepoRef, CachedValue<T>> map,
            RepoRef ref,
            Supplier<T> loader,
            Duration ttl)
    {
        CachedValue<T> existing = map.get(ref);
        if (existing != null && existing.isValid()) {
            return existing.value();
        }
        T fresh = loader.get();
        map.put(ref, new CachedValue<>(fresh, Instant.now().plus(ttl)));
        return fresh;
    }

    private record CachedValue<T>(T value, Instant expiresAt)
    {
        boolean isValid()
        {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
