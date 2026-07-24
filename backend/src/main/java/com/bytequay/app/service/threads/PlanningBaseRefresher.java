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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.pr.PullRequestMergedEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.requireNonNull;

/**
 * Keeps the remote-tracking refs of planning bases fresh in the
 * background so no agent turn ever fetches on its own path: the trunk's
 * turn-start sync only compares and applies what these fetches already
 * brought down.
 *
 * <p>Two triggers:
 * <ul>
 *   <li>a merged PR — the base branch just moved, fetch that repo's
 *       clone immediately so the very next trunk turn plans against the
 *       merged code;</li>
 *   <li>a periodic poll — every clone some recently-active thread plans
 *       against gets fetched, catching pushes made outside ByteQuay.</li>
 * </ul>
 *
 * <p>Fetches run on a single background thread, deduplicated per clone
 * while queued, and serialize with worktree operations through {@link
 * WorktreeService#fetchPlanningBase}'s per-clone lock. Refs only — a
 * planning worktree is never reset here, so running turns are unaffected.
 */
@Component
public class PlanningBaseRefresher
{
    /** A thread counts as recently active — and its planning base worth
     *  keeping fresh — when it was updated within this window. */
    private static final long ACTIVE_THREAD_WINDOW_MS = 60L * 60 * 1000;

    private final ThreadStore threadStore;
    private final WatchedRepoStore watchedRepos;
    private final WorktreeService worktrees;
    private final Executor fetcher;
    /** Clones with a fetch already queued; cleared when the fetch starts
     *  so a request arriving mid-fetch queues one more. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    @Autowired
    public PlanningBaseRefresher(
            ThreadStore threadStore, WatchedRepoStore watchedRepos, WorktreeService worktrees)
    {
        this(threadStore, watchedRepos, worktrees, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "planning-base-refresher");
            thread.setDaemon(true);
            return thread;
        }));
    }

    PlanningBaseRefresher(
            ThreadStore threadStore,
            WatchedRepoStore watchedRepos,
            WorktreeService worktrees,
            Executor fetcher)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.fetcher = requireNonNull(fetcher, "fetcher is null");
    }

    /** A base branch just moved — fetch the merged repo's clone now. */
    @EventListener
    public void onPullRequestMerged(PullRequestMergedEvent event)
    {
        watchedRepos.findAll().stream()
                .filter(repo -> repo.fullName().equalsIgnoreCase(event.repoFullName()))
                .map(WatchedRepo::localClonePath)
                .filter(path -> path != null && !path.isBlank())
                .forEach(this::enqueueFetch);
    }

    /** Catches base movement ByteQuay never sees an event for (pushes by
     *  other people, merges outside the app). */
    @Scheduled(fixedDelay = 120_000, initialDelay = 120_000)
    public void refreshActivePlanningBases()
    {
        Instant since = Instant.now().minusMillis(ACTIVE_THREAD_WINDOW_MS);
        threadStore.listActivePlanningRepoRoots(since).forEach(this::enqueueFetch);
    }

    private void enqueueFetch(String repoRoot)
    {
        if (!pending.add(repoRoot)) {
            return;
        }
        fetcher.execute(() -> {
            pending.remove(repoRoot);
            worktrees.fetchPlanningBase(Path.of(repoRoot));
        });
    }

    @PreDestroy
    void shutdown()
    {
        if (fetcher instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
