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
package com.bytequay.agent.cli.process;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Controls one supervised child process without exposing raw process streams. */
public interface ManagedProcess
        extends AutoCloseable
{
    /**
     * Returns the opaque launch identifier used for durable acknowledgement.
     *
     * @return agent-cli-local launch identifier
     */
    String launchId();

    /**
     * Returns the operating-system process ID of the payload, which also leads its process session.
     *
     * @return payload process ID
     */
    long pid();

    /**
     * Samples and durably records the descendants of a still-running payload.
     *
     * <p>A persistent IDE can compare samples before and after an execution without treating its
     * pre-existing language services as test processes. Known identities remain in later samples
     * after reparenting or exit. Absence means the payload ended or observation is incomplete;
     * it never means the tree is empty. This observation is not OS containment or a substitute
     * for the provider's execution-finished signal. Recovery still stops the entire owned launch.
     *
     * @return immutable cumulative process identities, or empty when they cannot be verified
     */
    Optional<List<ProcessIdentity>> observedDescendants();

    /**
     * Writes bytes to standard input in call order.
     *
     * @param bytes bytes to copy and write
     * @return stage completed when the bytes are flushed or the write fails
     */
    CompletionStage<Void> write(ByteBuffer bytes);

    /** Closes standard input without terminating the child. */
    void closeInput();

    /**
     * Returns the terminal process result after output has been drained and runtime cleanup
     * has been checked.
     *
     * <p>Root exit alone is never sufficient: the result distinguishes
     * {@code VERIFIED_STOPPED} from {@code UNVERIFIABLE} so a caller can keep a worktree-mutation
     * claim held when a session member survives.
     *
     * @return stage completed exactly once with the terminal result
     */
    CompletionStage<ProcessExit> exit();

    /** Requests idempotent, non-blocking process-tree cancellation. */
    void cancel();

    /**
     * Stops the process session and recorded descendants and returns a fresh verification result.
     *
     * <p>Unlike {@link #exit()}, this operation is retryable after an earlier exit result could not
     * verify the tree.
     *
     * @return completion with current runtime-stop verification
     */
    CompletionStage<ProcessExit.TreeTermination> stopAndVerify();

    /**
     * Records provider-confirmed completion of this launch's work, then stops its runtime.
     *
     * <p>The caller must have reconciled the current turn, its recursive children, and its tools
     * under the provider's lifecycle contract, and must deliver no further input. Implementations
     * may use that durable fact to resolve gaps in process observation; they must still stop the
     * runtime and every process identity they recorded. This is not operating-system containment.
     *
     * @return completion with current runtime-stop verification
     */
    CompletionStage<ProcessExit.TreeTermination> stopAfterWorkFinished();

    /** Releases streams and cancels the process when it is still running. */
    @Override
    void close();
}
