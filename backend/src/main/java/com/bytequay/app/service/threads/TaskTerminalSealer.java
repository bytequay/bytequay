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

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.review.ReviewRoundService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static java.util.Objects.requireNonNull;

/**
 * The one place every "a task just went terminal" path (remote merge/close
 * observed, in-app cancel) seals the task's open work, so no caller can
 * forget a piece: any still-open review round (its {@code review_round} run
 * included) and any still-open stage stop rendering as live once the task
 * itself is done. Purely a status seal — interrupting live agent
 * subprocesses is the caller's job, since {@link TaskLifecycleDriver} and
 * {@link TaskService} do that with different urgency (fire-and-forget vs.
 * blocking on the subprocess actually exiting).
 */
@Component
class TaskTerminalSealer
{
    private final StageStore stageStore;
    private final ReviewRoundService reviewRounds;

    TaskTerminalSealer(StageStore stageStore, ReviewRoundService reviewRounds)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
    }

    @Transactional
    void seal(String taskId, String reason)
    {
        reviewRounds.closeOpenRounds(taskId, reason);
        for (StageInstance stage : stageStore.findStagesByTask(taskId)) {
            if (stage.state() != StageState.CLOSED) {
                stageStore.closeStage(stage.id(), reason);
            }
        }
    }
}
