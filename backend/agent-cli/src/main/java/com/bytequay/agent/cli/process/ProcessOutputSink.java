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

/** Receives bounded child-process output in the order observed by the supervisor. */
@FunctionalInterface
public interface ProcessOutputSink
{
    /**
     * Accepts one immutable output chunk.
     *
     * @param stream source stream
     * @param bytes read-only bytes whose position starts at the first output byte
     */
    void onOutput(ProcessStream stream, ByteBuffer bytes);

    /**
     * Reports that further output from a stream was discarded after its bound.
     *
     * @param stream truncated stream
     */
    default void onTruncated(ProcessStream stream) {}
}
