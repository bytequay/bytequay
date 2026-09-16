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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.app.context.RepositoryContextPreparation;
import com.bytequay.app.context.RepositoryContextService;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.review.AgentReview;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Settles preparation ownership from existing durable runtime facts without starting or stopping an agent. */
@Component
public class ReviewContextPreparationCleanup
{
    private final RepositoryContextService notebooks;
    private final RepositoryContextPreparation preparation;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentUnitOfWork unitOfWork;

    public ReviewContextPreparationCleanup(
            RepositoryContextService notebooks, RepositoryContextPreparation preparation, AgentTaskStore tasks, AgentTurnStore turns, AgentUnitOfWork unitOfWork)
    {
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.preparation = requireNonNull(preparation, "preparation is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
    }

    /** Called under the owning review lock; a retained runtime claim keeps an interrupted owner fenced. */
    public void reconcile(AgentReview review)
    {
        WorkspaceOperationLocks.withLock(review.workspaceId(), () -> unitOfWork.execute(() -> {
            Optional<ActiveContextPreparation> found = notebooks.getNotebook(review.workspaceId()).activePreparation()
                    .filter(active -> active.reviewId().equals(review.id()) && active.ownerTaskId().equals(review.taskId()));
            if (found.isEmpty()) {
                return null;
            }
            ActiveContextPreparation active = found.orElseThrow();
            Optional<AgentTask> task = tasks.find(TaskId.valueOf(active.ownerTaskId()))
                    .filter(value -> value.workspaceId().filter(review.workspaceId()::equals).isPresent());
            if (task.isEmpty() || task.orElseThrow().worktreeMutationClaim().isPresent()) {
                return null;
            }
            Optional<AgentTurn> turn = turns.find(TurnId.valueOf(active.ownerTurnId()))
                    .filter(value -> value.taskId().equals(task.orElseThrow().id()) && value.idempotencyKey().equals(active.ownerTurnKey()));
            if (turn.isEmpty() || !turn.orElseThrow().state().terminal()) {
                return null;
            }
            String reason = switch (turn.orElseThrow().state()) {
                case COMPLETED -> "The investigation finished without submitting its shared knowledge preparation.";
                case CANCELLED -> "Shared knowledge preparation was stopped by the user.";
                default -> "The preparation turn ended before a shared knowledge report was accepted.";
            };
            preparation.fail(review.workspaceId(), active, reason);
            return null;
        }));
    }
}
