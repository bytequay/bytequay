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

import java.time.Duration;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Terminal child-process status and output-bound metadata. */
public record ProcessExit(
        int exitCode,
        Reason reason,
        Duration duration,
        boolean stdoutTruncated,
        boolean stderrTruncated)
{
    public ProcessExit
    {
        requireNonNull(reason, "reason is null");
        requireNonNull(duration, "duration is null");
        checkArgument(!duration.isNegative(), "duration is negative");
    }

    /** Explains why termination began before the operating-system exit was observed. */
    public enum Reason
    {
        EXITED,
        CANCELLED,
        TIMED_OUT
    }
}
