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

import com.bytequay.agent.spi.ProviderUsageSample;

/**
 * Converts provider usage samples into durable turn and task metrics.
 *
 * <p>The service owns cumulative-versus-delta semantics and duplicate
 * suppression so repeated provider snapshots are not summed twice. Missing
 * monetary cost remains unavailable rather than being recorded as zero.
 */
public interface AgentMetricsService
{
    /**
     * Captures task totals immediately before a provider turn starts.
     *
     * @param taskId task about to start a turn
     * @param turnId accepted turn
     * @return baseline for cumulative provider usage
     */
    UsageBaseline beginTurn(TaskId taskId, TurnId turnId);

    /**
     * Applies one normalized provider usage sample without double-counting repeated
     * snapshots or provider event identifiers.
     *
     * @param taskId task that owns the provider session
     * @param turnId turn receiving the sample
     * @param sample counters, semantics, cost, and provenance
     * @return latest persisted per-turn metrics
     * @throws AgentStorageException when metrics cannot be persisted
     */
    TurnMetrics record(TaskId taskId, TurnId turnId, ProviderUsageSample sample);

    /**
     * Finalizes elapsed time and usage for a terminal turn.
     *
     * @param turnId terminal turn
     * @param terminalState completed, failed, cancelled, or interrupted state
     * @return immutable final metrics
     * @throws AgentConflictException when {@code terminalState} is not terminal
     */
    TurnMetrics finishTurn(TurnId turnId, TurnState terminalState);

    /**
     * Returns durable totals across every turn in a task.
     *
     * @param taskId task whose totals are requested
     * @return token, cost, elapsed-time, and provenance totals
     */
    TaskMetrics taskTotals(TaskId taskId);
}
