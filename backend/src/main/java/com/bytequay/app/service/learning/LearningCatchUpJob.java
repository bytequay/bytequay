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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.repository.sqlite.SqliteWorkspaceStore;
import com.bytequay.app.scheduler.QuietHoursPolicy;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Daily learning maintenance, per repository with a learning run: resume
 * runs parked partial (rate limits, missing keys), continue low-priority
 * backfill for runs at 'useful', and revalidate active knowledge against the
 * current clone — re-confirming items whose anchors still exist and decaying
 * items whose anchors are gone. Learning failure never blocks ordinary
 * coding/review work; every workspace is isolated by its own try/catch.
 */
@Component
public class LearningCatchUpJob
{
    private static final Logger log = LoggerFactory.getLogger(LearningCatchUpJob.class);

    /** Low-priority backfill budget per repository per day. */
    private static final int DAILY_BACKFILL_CAP = 25;

    private final ProjectLearningService learning;
    private final ProjectLearningStore runs;
    private final KnowledgeItemStore knowledge;
    private final SqliteWorkspaceStore workspaces;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final QuietHoursPolicy quietHours;

    public LearningCatchUpJob(
            ProjectLearningService learning,
            ProjectLearningStore runs,
            KnowledgeItemStore knowledge,
            SqliteWorkspaceStore workspaces,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            QuietHoursPolicy quietHours)
    {
        this.learning = requireNonNull(learning, "learning is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.quietHours = requireNonNull(quietHours, "quietHours is null");
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT24H")
    public void tick()
    {
        if (quietHours.isQuietNow()) {
            return;
        }
        for (Workspace workspace : workspaces.listWorkspaces()) {
            try {
                catchUp(workspace.id());
            }
            catch (RuntimeException e) {
                log.warn("learning catch-up failed for workspace {}: {}",
                        workspace.id(), e.getMessage());
            }
        }
    }

    /** Package-private so tests can drive one workspace synchronously. */
    void catchUp(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity identity;
        try {
            identity = repositories.resolve(workspaceId);
        }
        catch (RuntimeException e) {
            return;         // workspace has no bound repository
        }
        String repo = identity.fullName();
        ProjectLearningRun run = runs.latestRun(workspaceId, repo).orElse(null);
        if (run == null) {
            return;         // never learned; nothing to maintain
        }
        switch (run.state()) {
            case "partial" -> learning.retry(run.id());
            case "useful" -> learning.backfill(workspaceId, repo, DAILY_BACKFILL_CAP);
            case "caught-up" -> learning.refreshCompleted(run.id());
            default -> {}
        }
        revalidate(workspaceId, repo, clonePath(identity));
    }

    /**
     * Targeted revalidation: only items carrying path anchors are checked,
     * against the current clone. Anchors present → re-confirmed (validation
     * stamp refreshed); anchors all gone → decayed, never silently applied
     * as current truth again.
     */
    // ponytail: checks anchor existence per item instead of intersecting a
    // git diff since the last validation; switch to changed-path
    // intersection if daily scans ever measure slow.
    private void revalidate(String workspaceId, String repo, Path clone)
    {
        if (clone == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        for (KnowledgeItem item : knowledge.listByLifecycle(
                workspaceId, KnowledgeItem.LIFECYCLE_ACTIVE)) {
            if (!repo.equals(item.repo())) {
                continue;
            }
            List<KnowledgeItem.Applicability> paths = knowledge.applicability(item.id())
                    .stream()
                    .filter(tag -> "path".equals(tag.kind()))
                    .toList();
            if (paths.isEmpty()) {
                continue;   // nothing verifiable; leave as-is
            }
            boolean anyPresent = paths.stream().anyMatch(tag -> {
                Path resolved = clone.resolve(tag.value()).normalize();
                return resolved.startsWith(clone.normalize()) && Files.exists(resolved);
            });
            if (anyPresent) {
                knowledge.setLifecycle(item.id(), KnowledgeItem.LIFECYCLE_ACTIVE, null, now);
            }
            else {
                knowledge.setLifecycle(item.id(), KnowledgeItem.LIFECYCLE_DECAYED, null, now);
                log.info("decayed knowledge {} — no path anchor exists anymore", item.id());
            }
        }
    }

    private Path clonePath(WorkspaceRepositoryResolver.RepositoryIdentity identity)
    {
        return watchedRepos.find(identity.owner(), identity.repo())
                .map(WatchedRepo::localClonePath)
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of)
                .filter(Files::isDirectory)
                .orElse(null);
    }
}
