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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.TaskCheckpoint;

import java.util.Optional;

/**
 * Hook point the agent session calls after every successful turn so a
 * {@code CheckpointScheduler} can decide whether to generate a new
 * checkpoint segment. The interface lets us inject a no-op into test
 * fixtures and any non-CLI session impls without dragging in the
 * scheduler's Anthropic client.
 */
public interface CheckpointTrigger
{
    /** A no-op trigger for tests and code paths that don't run a
     *  scheduler. Returns {@link Optional#empty()} from
     *  {@link #manualGenerate} so a caller can distinguish "I asked
     *  for a checkpoint but none was produced" from "the trigger
     *  doesn't generate checkpoints at all". */
    CheckpointTrigger NOOP = new CheckpointTrigger()
    {
        @Override
        public void onTurnDone(String taskId) {}

        @Override
        public Optional<TaskCheckpoint> manualGenerate(String taskId)
        {
            return Optional.empty();
        }
    };

    /** Fired after a {@code TurnDone} stream event has been persisted.
     *  Implementations decide whether the task has crossed the
     *  per-task token threshold and should generate a new segment.
     *  Must return promptly — the call is on the session's event
     *  thread; any heavy lifting (Anthropic call) belongs on a
     *  background executor. */
    void onTurnDone(String taskId);

    /** Force-generate a segment for any messages that have landed
     *  since the last segment, regardless of threshold. Drives the
     *  UI's "+ save checkpoint" button. Returns the new segment when
     *  one was produced, or empty when there was nothing new to
     *  summarise (button is disabled in that state anyway). */
    Optional<TaskCheckpoint> manualGenerate(String taskId);
}
