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

/**
 * Durable launch intent recorded by the owner of a supervised provider process.
 *
 * <p>An absent row means the launch was never attempted. The finer process fact lives in the
 * agent-cli launch marker; recovery classifies a launch from both.
 */
public enum LaunchIntentState
{
    /** A launch identifier exists and no process has been spawned. */
    PREPARED,
    /** The owner is about to spawn the payload; the outcome is not yet known. */
    ATTEMPTING,
    /** The gate was released, so the payload was executed. */
    RUNNING,
    /** The process tree reached a terminal receipt that is not yet acknowledged. */
    TERMINAL
}
