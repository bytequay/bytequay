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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.SqliteReviewRoundStore;
import com.bytequay.app.service.localpr.PRService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Read projection for historical LEGACY review rounds. */
@Service
public class ReviewRoundServiceImpl
{
    private final TaskStore tasks;
    private final SqliteReviewRoundStore rounds;
    private final PRService prs;

    public ReviewRoundServiceImpl(TaskStore tasks, SqliteReviewRoundStore rounds, PRService prs)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.rounds = requireNonNull(rounds, "rounds is null");
        this.prs = requireNonNull(prs, "prs is null");
    }

    public Optional<ReviewRound> findById(String roundId)
    {
        return rounds.findById(roundId);
    }

    public List<ReviewRound> findByTask(String taskId)
    {
        List<ReviewRound> found = rounds.findByTask(taskId);
        boolean parked = tasks.findTaskById(taskId)
                .map(task -> task.status() == TaskStatus.PAUSED
                        || task.status() == TaskStatus.NEEDS_ATTENTION
                        || task.phase() == TaskPhase.NEEDS_ATTENTION)
                .orElse(false);
        int openBrainFindings = prs.findByTask(taskId)
                .map(pr -> (int) prs.comments(pr.id()).stream()
                        .filter(comment -> PRTimelineEntry.ACTOR_BRAIN.equals(comment.author()))
                        .filter(comment -> comment.parentCommentId() == null)
                        .filter(comment -> comment.resolvedAt() == null
                                && comment.dismissedAt() == null)
                        .count())
                .orElse(0);
        return found.stream().map(round -> project(round, parked, openBrainFindings)).toList();
    }

    private static ReviewRound project(ReviewRound round, boolean parked, int openBrainFindings)
    {
        ReviewRound projected = parked && round.isLive()
                ? round.withStatus(ReviewRound.STATUS_PAUSED)
                : round;
        if (!ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            return projected;
        }
        ReviewRound.ReviewRoundStats stats = round.stats() == null
                ? ReviewRound.ReviewRoundStats.empty()
                : round.stats();
        return projected.withStats(new ReviewRound.ReviewRoundStats(
                stats.fixed(), stats.replied(), stats.pushedBack(), openBrainFindings));
    }

    private static ResponseStatusException retired()
    {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "LEGACY review rounds are read-only; use typed V2 review controls");
    }
}
