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
package com.bytequay.app.flow.upstream;

import com.bytequay.app.flow.timeline.TaskViews;
import com.bytequay.app.flow.timeline.TaskViews.TaskSummary;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PrResult;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Learns that a run's pull request has ended.
 *
 * <p>Merging is the user's act, not the run's, so the only way the run can
 * know is to be told by the provider. Polled on a background cadence — never
 * on a user's turn — and written once: a merge does not become a close later.
 *
 * <p>This observes; it does not release. Releasing the worktree, the branches
 * and the session is the flow runtime's to own, and until it does the run's
 * cleanup receipt says so rather than ticking steps nothing performed.
 */
public final class UpstreamSyncClosureObserver
{
    private static final Logger log = LoggerFactory.getLogger(
            UpstreamSyncClosureObserver.class);

    /** How a pull request ended, read from the provider. */
    public interface PullRequestEndProbe
    {
        /**
         * @return empty while the pull request is still open, and equally when
         *         it cannot be read — an unanswered probe is not an ending.
         */
        Optional<PrResult> observe(String repositoryId, long prNumber);
    }

    private final UpstreamSync sync;
    private final TaskViews tasks;
    private final PullRequestEndProbe probe;

    public UpstreamSyncClosureObserver(
            UpstreamSync sync, TaskViews tasks, PullRequestEndProbe probe)
    {
        this.sync = requireNonNull(sync, "sync is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.probe = requireNonNull(probe, "probe is null");
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void observeEndedPullRequests()
    {
        for (UpstreamSyncRun run : sync.runsAwaitingPullRequestEnd()) {
            try {
                observe(run);
            }
            catch (RuntimeException failure) {
                // One unreachable repository must not stop the others, and a
                // failed probe leaves the run exactly as it was.
                log.warn("reading the pull request state for sync run {} "
                        + "failed: {}", run.runId(), failure.getMessage());
            }
        }
    }

    private void observe(UpstreamSyncRun run)
    {
        Optional<TaskSummary> task = tasks.summary(run.taskId());
        if (task.isEmpty() || task.orElseThrow().prNumber() == null) {
            // Nothing was published, so there is nothing to end yet.
            return;
        }
        TaskSummary summary = task.orElseThrow();
        probe.observe(summary.repositoryId(), summary.prNumber())
                .ifPresent(result -> {
                    sync.recordPullRequestEnd(run.runId(), result);
                    log.info("sync run {} pull request #{} {}",
                            run.runId(), summary.prNumber(),
                            result == PrResult.MERGED ? "merged" : "closed");
                });
    }
}
