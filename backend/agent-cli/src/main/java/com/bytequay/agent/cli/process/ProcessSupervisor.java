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

import com.bytequay.agent.spi.AgentUnavailableException;

import java.util.List;
import java.util.Optional;

/** Starts and reconciles bounded argv-based child processes for CLI-backed agent drivers. */
public interface ProcessSupervisor
{
    /**
     * Creates and fsyncs a launch marker without starting a process.
     *
     * <p>The caller persists the returned launch ID and its {@code PREPARED} launch intent in its
     * own unit of work before invoking {@link PreparedProcess#start}. This closes the gap between
     * durable state and an operating-system process.
     *
     * @param spec owner, executable, arguments, environment allowlist, directory, and limits
     * @return durable prepared launch; no payload process exists yet
     * @throws AgentUnavailableException when the executable or marker cannot be prepared
     */
    PreparedProcess prepare(ProcessSpec spec);

    /**
     * Terminates provider processes owned by an earlier backend instance.
     *
     * <p>The supervisor reads owner-only launch markers and signals a PID only when its executable
     * and operating-system process-start token still match. It waits for the complete process tree
     * to exit and returns an unverifiable result rather than risking an unrelated process.
     *
     * @return one terminal or unverifiable result per foreign launch marker
     */
    List<OwnedProcessRecovery> recoverOwnedProcesses();

    /**
     * Retries recovery for one durable launch without acknowledging its marker.
     *
     * <p>This is the stateless counterpart to startup recovery: callers name the launch retained
     * by their own durable claim, commit any safe release, and only then call
     * {@link #acknowledge(String)}. An unverifiable result leaves the marker available for another
     * explicit retry.
     *
     * @param launchId agent-cli-local opaque launch identifier
     * @return current recovery result for that launch
     */
    default OwnedProcessRecovery recoverOwnedProcess(String launchId)
    {
        return new OwnedProcessRecovery(
                launchId,
                Optional.empty(),
                Optional.empty(),
                OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                "Targeted recovery is not supported by this supervisor");
    }

    /**
     * Deletes a terminal launch marker and its receipt only after the owning state transition has
     * committed durably.
     *
     * @param launchId agent-cli-local opaque launch identifier
     */
    void acknowledge(String launchId);
}
