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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Durable launch intent committed by the owner of a supervised process.
 *
 * <p>An absent intent means the launch was never committed. The intent says what the owner decided;
 * the fsynced launch marker says what the operating system actually did. Recovery needs both,
 * because either one alone can be stale after a crash.
 *
 * @param launchId opaque launch identifier
 * @param state committed intent
 */
public record LaunchIntent(String launchId, State state)
{
    public LaunchIntent
    {
        requireNonNull(launchId, "launchId is null");
        checkArgument(!launchId.isBlank(), "launchId is blank");
        requireNonNull(state, "state is null");
    }

    /** How far the owner had committed when it last wrote this intent. */
    public enum State
    {
        /** A launch identifier exists and no process has been spawned. */
        PREPARED,
        /** The owner is about to spawn the payload; the outcome is not yet known. */
        ATTEMPTING,
        /** The gate was released, so the payload was executed. */
        RUNNING,
        /** A terminal receipt is committed and awaits acknowledgement. */
        TERMINAL
    }
}
