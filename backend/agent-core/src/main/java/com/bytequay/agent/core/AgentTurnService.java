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
package com.bytequay.agent.core;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Serializes turns and coordinates execution in a task's provider session.
 *
 * <p>This is the only core service that starts or cancels provider execution.
 * At most one turn may be active for a task, and later turns resume the task's
 * persisted provider session.
 */
public interface AgentTurnService
{
    /**
     * Accepts one user message and starts a provider turn asynchronously.
     *
     * <p>A repeated idempotency key returns the previously accepted turn. A new
     * request conflicts while another turn is active. The provider session
     * reference is persisted before the turn is reported as resumable.
     *
     * @param command task, message, attachments, and idempotency key
     * @return accepted or running turn
     * @throws AgentTaskNotFoundException when the task does not exist
     * @throws AgentConflictException when another turn is active
     */
    AgentTurn start(StartAgentTurnCommand command);

    /** Starts one bounded internal follow-up using its source user turn's immutable settings. */
    CompletionStage<DevelopmentTurnCompletion> startGeneratedTurn(TaskId taskId, DevelopmentTurnOrigin origin, String message);

    /**
     * Changes private review ownership only while the task is idle and has no queued input.
     * The synchronous callback verifies or restores the worktree before the returned head is stored.
     */
    AgentTask updateLocalReview(TaskId taskId, long expectedVersion, Function<AgentTask, Optional<String>> action);

    /** Holds the task's mutation ownership throughout a synchronous worktree update, excluding turns and queued input. */
    void synchronizeWorktree(TaskId taskId, long expectedVersion, Consumer<AgentTask> action);

    /**
     * Holds the idle task's worktree for one user-requested local run, excluding turns, queued input and other writers.
     *
     * @return the task carrying the {@code LOCAL_RUN} claim owned by the run
     * @throws AgentConflictException when a turn, queued input or another claim holds the task
     */
    AgentTask claimLocalRun(TaskId taskId, String runId);

    /** Releases the run's claim once its processes are verified stopped; a no-op when that run holds no claim. */
    void releaseLocalRun(TaskId taskId, String runId);

    /** Gives previously accepted FIFO user input the task atomically and ends this continuation. */
    boolean handoffDevelopmentContinuationToQueue(TaskId taskId, TurnId sourceTurnId);

    /** Releases a settled continuation; queued questions remain paused until explicitly resumed. */
    void finishDevelopmentContinuation(TaskId taskId, TurnId sourceTurnId);

    /** Revokes live authority and stops known writers, preserving the open task after verified shutdown. */
    AgentTask failDevelopmentContinuation(TaskId taskId, TurnId sourceTurnId, String reason);

    /** Stops an unfinished continuation whose backend was lost, preserving the task without replaying work. */
    AgentTask failLostDevelopmentContinuation(TaskId taskId);

    /** Whether this backend still authorizes the source user turn's continuation. */
    boolean ownsDevelopmentContinuation(TaskId taskId, TurnId sourceTurnId);

    /** Runs a program action under live continuation ownership; Stop drains admitted actions before releasing the task. */
    void executeDevelopmentAction(TaskId taskId, TurnId sourceTurnId, Runnable action);

    /** Appends an observed progress message to the existing task lifecycle history while this source owns delivery. */
    void reportDevelopmentProgress(TaskId taskId, TurnId sourceTurnId, String message);

    /** Returns a turn already accepted under the task-scoped request key. */
    Optional<AgentTurn> findTurnByIdempotencyKey(TaskId taskId, String idempotencyKey);

    /**
     * Returns the settings the next turn on this task would be given.
     *
     * <p>Derived from exactly what {@link #start} would snapshot, so the surface that shows the
     * user what is about to run cannot drift from what actually runs. Attachments belong to a
     * specific message, so the returned settings carry none.
     *
     * @param taskId task to describe
     * @return the settings a turn started now would record
     * @throws AgentTaskNotFoundException when the task does not exist
     */
    AgentTurnSettings effectiveSettings(TaskId taskId);

    /**
     * Adds input to the turn running now, without creating another turn.
     *
     * <p>The delivery row and the user-message event are durable before the one send attempt.
     * Refused when the provider does not steer or the named turn is not the task's running turn.
     * A repeated idempotency key returns the durable delivery. A send that got no answer is
     * {@code UNKNOWN} and is never retried by ByteQuay, because the provider has no key that would
     * let it drop a duplicate.
     *
     * @param command turn, text, attachments, and idempotency key
     * @return the delivery and its outcome
     * @throws AgentTurnNotFoundException when the turn does not exist
     * @throws AgentConflictException when the turn is not the running one
     * @throws com.bytequay.agent.spi.AgentUnavailableException when the provider cannot steer
     */
    AgentInputDelivery steer(SteerAgentTurnCommand command);

    /**
     * Transfers an unclaimed queued input into the named running turn. Queue consumption and
     * steer acceptance commit together; retries return the same delivery without resending it.
     */
    AgentInputDelivery steerQueued(TaskId taskId, TurnId turnId, QueuedInputId queuedInputId);

    /**
     * Finds a steer key before a caller resolves mutable request resources.
     *
     * <p>A settled result may be returned directly. {@code ACCEPTED} and {@code SENDING} are only
     * a signal to skip request prevalidation and re-enter {@link #steer}: core must resume the
     * original durable input or settle the in-progress attempt under its delivery lock.
     *
     * @throws AgentConflictException when the key belongs to another turn on the same task
     */
    Optional<AgentInputDelivery> findSteerByIdempotencyKey(TurnId turnId, String idempotencyKey);

    /**
     * Adds a request to the task's FIFO queue, to run after the current turn.
     *
     * <p>Snapshots the text, attachments, and the settings in force. Bumps the task version it was
     * given, so an enqueue racing Complete either lands before completion checks the queue or
     * conflicts. Idempotent by key; never calls the provider.
     *
     * @throws AgentConflictException when the version is stale or the task cannot take later work
     */
    QueuedAgentInput enqueue(QueueAgentInputCommand command);

    /** Returns queued input already accepted under the task-scoped request key. */
    Optional<QueuedAgentInput> findQueuedByIdempotencyKey(TaskId taskId, String idempotencyKey);

    /** The inputs still waiting, being claimed, or uncertain, in queue order. */
    List<QueuedAgentInput> queued(TaskId taskId);

    /**
     * Removes a waiting input, or abandons an uncertain one.
     *
     * <p>Abandoning records that the user chose not to resend it; it does not claim the provider
     * never received it.
     *
     * @param taskId task that owns the input
     * @param queuedInputId input to remove
     * @throws AgentConflictException when the input belongs to another task or was already consumed
     */
    QueuedAgentInput removeQueued(TaskId taskId, QueuedInputId queuedInputId);

    /**
     * Starts the queue head on an idle task after a non-success terminal turn or a restart.
     *
     * <p>A successful turn starts the next input by itself; this is the explicit path for a queue
     * that paused. An uncertain head is refused until it is discarded.
     *
     * @return the turn created from the head
     * @throws AgentConflictException when the task is not idle, the queue is empty, or its head is uncertain
     */
    AgentTurn resumeQueued(TaskId taskId);

    /**
     * Requests cancellation of an accepted or running turn.
     *
     * <p>The request is idempotent. Terminal state is recorded only after the
     * provider exits or the cancellation grace period expires.
     *
     * @param turnId turn to cancel
     * @return latest durable turn state after recording the request
     * @throws AgentTurnNotFoundException when the turn does not exist
     */
    AgentTurn cancel(TurnId turnId);

    /**
     * Describes what stopping the task's provider process would interrupt.
     *
     * <p>The returned generation names the runtime the caller looked at: the active turn when
     * there is one, otherwise the task version. {@link #stopRuntime} requires it back, so a turn
     * that started after the caller confirmed cannot be stopped by that confirmation.
     *
     * @param taskId task whose runtime is described
     * @return the impact and the generation a stop must echo
     * @throws AgentTaskNotFoundException when the task does not exist
     */
    AgentRuntimeStopImpact stopImpact(TaskId taskId);

    /**
     * Stops the task's provider process and any CI work owned by its development continuation.
     *
     * <p>Development authority is revoked before stopping owned children. The parent stays blocked
     * until child and source writers are verified stopped. The active turn, if any, is interrupted
     * and its pending approvals denied. The task's worktree claim is
     * released only once the process tree is verified stopped; a process that cannot be verified
     * keeps the task blocked and this method fails. A claim left by an earlier turn whose provider
     * could not be shown quiet is released by the same verification, because the process that
     * might still have been writing is now provably gone.
     *
     * <p>A stopped development continuation preserves the open task, files, and history. Waiting
     * inputs stay paused; only a new user action starts more work.
     *
     * @param taskId task whose process should stop
     * @param generation the value returned by {@link #stopImpact}
     * @return the open task after the stop
     * @throws AgentTaskNotFoundException when the task does not exist
     * @throws AgentConflictException when the task is terminal or the generation is stale
     * @throws AgentUnavailableException when the process tree could not be verified stopped
     */
    AgentTask stopRuntime(TaskId taskId, String generation);

    /**
     * Returns the latest durable state and metrics for one turn.
     *
     * @param turnId turn to read
     * @return stored turn
     * @throws AgentTurnNotFoundException when the turn does not exist
     */
    AgentTurn get(TurnId turnId);
}
