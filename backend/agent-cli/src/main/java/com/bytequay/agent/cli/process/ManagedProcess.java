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
import java.util.concurrent.CompletionStage;

/** Controls one supervised child process without exposing raw process streams. */
public interface ManagedProcess
        extends AutoCloseable
{
    /**
     * Returns the operating-system process ID.
     *
     * @return child process ID
     */
    long pid();

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
     * Returns the terminal process result after output has been drained.
     *
     * @return stage completed exactly once with the terminal result
     */
    CompletionStage<ProcessExit> exit();

    /** Requests idempotent, non-blocking process-tree cancellation. */
    void cancel();

    /** Releases streams and cancels the process when it is still running. */
    @Override
    void close();
}
