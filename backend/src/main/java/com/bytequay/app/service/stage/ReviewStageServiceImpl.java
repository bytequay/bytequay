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
package com.bytequay.app.service.stage;

import com.bytequay.app.beans.stage.SpawnReviewResult;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPassKind;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.review.ReviewPassService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Opens a callable {@code REVIEW_STAGE} and seats a TASK_PHASE-hosted
 * review pass over the owning task's PR, stamping the stage ↔ pass link so
 * the terminate hook can close the right stage. The heavy panel body runs
 * off-request inside {@link ReviewPassService}.
 */
@Service
public class ReviewStageServiceImpl
        implements ReviewStageService
{
    private static final Logger log = LoggerFactory.getLogger(ReviewStageServiceImpl.class);

    /** A panel review is only callable while the task is reviewing its own
     *  work before pushing — the internal-review context. */
    private static final Set<TaskPhase> REVIEWABLE_PHASES = EnumSet.of(TaskPhase.INTERNAL_REVIEW);

    private final StageStore stageStore;
    private final TaskStore taskStore;
    private final WatchedRepoStore watchedRepoStore;
    private final ReviewPassService reviewPassService;

    public ReviewStageServiceImpl(
            StageStore stageStore,
            TaskStore taskStore,
            WatchedRepoStore watchedRepoStore,
            ReviewPassService reviewPassService)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.reviewPassService = requireNonNull(reviewPassService, "reviewPassService is null");
    }

    @Override
    public SpawnReviewResult spawnReview(UUID parentStageId)
    {
        StageInstance parent = stageStore.findStageById(parentStageId)
                .orElseThrow(() -> status(404, "no stage: " + parentStageId));
        if (parent.state() == StageState.CLOSED) {
            throw status(409, "parent stage is closed");
        }
        Task task = taskStore.findTaskById(parent.taskId())
                .orElseThrow(() -> status(404, "no task: " + parent.taskId()));
        if (!REVIEWABLE_PHASES.contains(task.phase())) {
            throw status(409, "task is not in an internal-review phase (" + task.phase() + ")");
        }
        if (task.prNumber() == null || task.prNumber() <= 0) {
            throw status(409, "task has no PR to review yet");
        }
        String repoFullName = resolveRepoFullName(task)
                .orElseThrow(() -> status(409, "can't resolve the repo for this task's PR"));

        // Open the callable sub-stage first so the pass can be stamped with
        // its id; caller = the parent stage the review was launched from.
        StageInstance reviewStage = stageStore.openStage(
                task.id(), StageType.REVIEW_STAGE, parent.id());

        // Seat a TASK_PHASE-hosted FRESH pass linked back to the stage; the
        // link is stamped during seating (before the async body can settle),
        // so the terminate hook always sees it. The panel body runs async.
        ReviewPassDetail detail = reviewPassService.startTaskPhaseReview(
                task.id(), repoFullName, task.prNumber(), ReviewPassKind.FRESH,
                ReviewPassService.StartOptions.DEFAULT, reviewStage.id().toString());
        String reviewPassId = detail.pass().id();

        log.info("Spawned review pass {} under stage {} (caller {}) for task {} PR #{}",
                reviewPassId, reviewStage.id(), parent.id(), task.id(), task.prNumber());
        return new SpawnReviewResult(
                reviewStage.id().toString(), reviewPassId, detail.pass().threadId());
    }

    /**
     * The repo the task's PR lives in. A dev task reviews its own PR, so
     * the worktree's watched repo is the primary source; a linked-PR task
     * carries the {@code owner/repo#n} ref instead.
     */
    private Optional<String> resolveRepoFullName(Task task)
    {
        Optional<String> fromWorktree = findRepoForWorkingDir(task.workingDir())
                .map(WatchedRepo::fullName);
        if (fromWorktree.isPresent()) {
            return fromWorktree;
        }
        String linkedPrRef = task.linkedPrRef();
        if (linkedPrRef != null && !linkedPrRef.isBlank()) {
            int hash = linkedPrRef.indexOf('#');
            return Optional.of(hash < 0 ? linkedPrRef : linkedPrRef.substring(0, hash));
        }
        return Optional.empty();
    }

    private Optional<WatchedRepo> findRepoForWorkingDir(String workingDir)
    {
        if (workingDir == null || workingDir.isBlank()) {
            return Optional.empty();
        }
        Path needle = Path.of(workingDir);
        for (WatchedRepo r : watchedRepoStore.findAll()) {
            if (r.localClonePath() != null
                    && !r.localClonePath().isBlank()
                    && Path.of(r.localClonePath()).equals(needle)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
