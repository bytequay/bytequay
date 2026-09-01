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

/**
 * Durable launch-marker phase recorded by the supervisor before and around the payload spawn.
 *
 * <p>{@link #RELEASE_PENDING} is conservatively treated as possibly executed even when the final
 * {@link #RUNNING} write was never observed.
 */
public enum LaunchPhase
{
    /** Marker exists and no process has been spawned. */
    PREPARED,
    /** A gated launcher exists and its identity is durable; the payload has not been executed. */
    GATED,
    /** The gate is about to be released; the payload may have executed. */
    RELEASE_PENDING,
    /** The gate was released and the payload was executed. */
    RUNNING,
    /** The process tree reached a terminal receipt that is not yet acknowledged. */
    TERMINAL
}
