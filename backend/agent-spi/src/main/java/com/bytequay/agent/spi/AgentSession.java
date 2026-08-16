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
package com.bytequay.agent.spi;

/**
 * Represents one open, resumable provider conversation for an agent task.
 *
 * <p>The session is fixed to the task's lane, worktree, resources, and
 * permission policy. It permits one active turn at a time; closing releases
 * local transports without deleting provider-owned conversation history.
 */
public interface AgentSession
        extends AutoCloseable
{
    /**
     * Returns the opaque provider reference ByteQuay must persist for later turns.
     *
     * @return resumable provider session reference
     */
    AgentSessionRef reference();

    /**
     * Starts one turn in this session.
     *
     * <p>The caller guarantees that no other turn is active. Provider output is
     * delivered through the sink supplied when this session was opened.
     *
     * @param command provider-neutral prompt, attachments, and runtime bound
     * @return handle for completion and cancellation
     * @throws AgentUnavailableException when the provider cannot start the turn
     */
    RunningTurn start(AgentTurnCommand command);

    /**
     * Releases transports and processes owned by this session.
     *
     * <p>Closing is idempotent and does not delete provider-owned history.
     */
    @Override
    void close();
}
