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

import java.util.Map;

/**
 * Commits the durable ownership record for a supervised provider process.
 *
 * <p>{@code agent-cli} declares this port and never implements durable storage itself. The
 * supervisor calls it around the two transitions that recovery can act on, so no caller can spawn a
 * payload without a committed owner, and so a crash always leaves a record naming whatever might
 * still be running.
 *
 * <p>The record deliberately carries one fact and not a lifecycle. Everything the operating system
 * did is in the fsynced launch marker; duplicating it here would only create two state machines
 * that must be kept in step, and one more write that could fail while the process is healthy.
 */
public interface LaunchIntentRecorder
{
    /**
     * Commits a prepared launch before any process exists.
     *
     * @param launchId opaque launch identifier
     * @param owner closed owner kind and opaque owner identifier
     * @param executable resolved executable path
     * @param argvDigest digest of the argument vector; never the arguments themselves
     */
    void prepared(String launchId, ProcessOwner owner, String executable, String argvDigest);

    /** Commits that the payload is about to be spawned, before any launcher exists. */
    void attempting(String launchId);

    /**
     * Returns every committed launch and whether its owner had committed to spawning.
     *
     * @return launch identifier to attempted flag; an absent key was never committed at all
     */
    Map<String, Boolean> committedLaunches();

    /** Deletes an acknowledged or rolled-back launch; absent records are ignored. */
    void forget(String launchId);
}
