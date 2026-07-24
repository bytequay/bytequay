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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPlanningBaseRefresher
{
    private static final String CLONE_PATH = "/tmp/acme-widgets";

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
    private final WorktreeService worktrees = mock(WorktreeService.class);

    private PlanningBaseRefresher newRefresher(Executor executor)
    {
        return new PlanningBaseRefresher(threadStore, watchedRepos, worktrees, executor);
    }

    @Test
    void mergedPrFetchesTheMergedReposClone()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                new WatchedRepo(1, "acme", "widgets", 0, CLONE_PATH, null, null)));

        newRefresher(Runnable::run)
                .onPullRequestMerged(new PullRequestMergedEvent("Acme/Widgets", 44));

        verify(worktrees).fetchPlanningBase(Path.of(CLONE_PATH));
    }

    @Test
    void mergedPrWithoutLocalCloneIsIgnored()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                new WatchedRepo(1, "acme", "widgets", 0, null, null, null),
                new WatchedRepo(2, "other", "repo", 1, "/tmp/other-repo", null, null)));

        newRefresher(Runnable::run)
                .onPullRequestMerged(new PullRequestMergedEvent("acme/widgets", 44));

        verify(worktrees, never()).fetchPlanningBase(any());
    }

    @Test
    void pollFetchesEveryActivePlanningRoot()
    {
        when(threadStore.listActivePlanningRepoRoots(any(Instant.class)))
                .thenReturn(List.of(CLONE_PATH, "/tmp/other-repo"));

        newRefresher(Runnable::run).refreshActivePlanningBases();

        verify(worktrees).fetchPlanningBase(Path.of(CLONE_PATH));
        verify(worktrees).fetchPlanningBase(Path.of("/tmp/other-repo"));
    }

    @Test
    void queuedFetchForTheSameCloneIsNotDuplicated()
    {
        when(threadStore.listActivePlanningRepoRoots(any(Instant.class)))
                .thenReturn(List.of(CLONE_PATH));
        List<Runnable> queued = new ArrayList<>();
        PlanningBaseRefresher refresher = newRefresher(queued::add);

        refresher.refreshActivePlanningBases();
        refresher.refreshActivePlanningBases();
        assertThat(queued).hasSize(1);

        queued.forEach(Runnable::run);
        verify(worktrees, times(1)).fetchPlanningBase(Path.of(CLONE_PATH));

        // once the queued fetch ran, a new request queues again
        refresher.refreshActivePlanningBases();
        assertThat(queued).hasSize(2);
    }
}
