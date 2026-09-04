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

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Operating-system identity of one observed process.
 *
 * <p>The start token distinguishes a live process from a later reuse of the same PID. A PID alone
 * is never sufficient to signal a process owned by an earlier backend instance.
 *
 * @param pid operating-system process identifier
 * @param startToken operating-system process start instant
 */
public record ProcessIdentity(long pid, Instant startToken)
{
    public ProcessIdentity
    {
        checkArgument(pid > 0, "pid is not positive");
        requireNonNull(startToken, "startToken is null");
    }

    /**
     * Reports whether a live process still carries this exact identity.
     *
     * <p>An unreadable start token is {@link Liveness#UNKNOWN} rather than {@code GONE}: failing to
     * inspect a process is never evidence that it exited.
     *
     * @return matched, gone, or unknown
     */
    public Liveness liveness()
    {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return Liveness.GONE;
        }
        Optional<Instant> observed;
        try {
            observed = handle.orElseThrow().info().startInstant();
        }
        catch (RuntimeException failure) {
            return Liveness.UNKNOWN;
        }
        if (observed.isEmpty()) {
            return Liveness.UNKNOWN;
        }
        return observed.orElseThrow().equals(startToken) ? Liveness.MATCHES : Liveness.GONE;
    }

    /** Whether the recorded identity still names a live process. */
    public enum Liveness
    {
        /** The PID exists and reports the recorded start token. */
        MATCHES,
        /** The PID is absent, or belongs to a later process that reused the identifier. */
        GONE,
        /** The PID exists but could not be inspected, so nothing may be concluded. */
        UNKNOWN
    }
}
