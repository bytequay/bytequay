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

/** One durably prepared launch whose payload process does not exist yet. */
public interface PreparedProcess
{
    /**
     * Returns the durable launch identifier recorded by the prepared marker.
     *
     * @return agent-cli-local opaque launch identifier
     */
    String launchId();

    /**
     * Starts the already prepared argv-based payload behind a parent-held gate.
     *
     * <p>No shell interpretation is applied to the payload arguments. Stdout and stderr are drained
     * independently, output limits are enforced, and the sink is called in observed stream order. A
     * failure before the gate is released leaves no payload process and removes the marker.
     *
     * @param output bounded output consumer
     * @return managed payload process
     * @throws AgentUnavailableException when the gated launch cannot be completed
     */
    ManagedProcess start(ProcessOutputSink output);

    /** Removes a known-unsent prepared marker after the owner rolls its launch intent back. */
    void abandon();
}
