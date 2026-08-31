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
package com.bytequay.agent.spi;

import java.util.List;
import java.util.Optional;

/**
 * Adapts one provider lane to ByteQuay's resumable session contract.
 *
 * <p>Implementations probe their native CLI or protocol, open or resume
 * sessions, and translate provider wire messages into {@link ProviderAgentEvent}
 * values. Raw provider protocol objects never cross this SPI boundary.
 */
public interface AgentDriver
{
    /**
     * Returns the stable lane identifier persisted with every task.
     *
     * @return provider lane identifier
     */
    AgentLaneId id();

    /**
     * Human-readable provider name for settings surfaces.
     *
     * @return display label
     */
    default String label()
    {
        return id().value();
    }

    /**
     * Describes event fidelity and features supported by this driver version.
     *
     * @return immutable provider capabilities
     */
    AgentCapabilities capabilities();

    /**
     * Checks binary, authentication, and protocol readiness without opening a task.
     *
     * @return readiness and actionable diagnostic information
     */
    AgentProviderStatus probe();

    /**
     * Enumerates the models this provider currently offers, when the provider
     * can report them. Drivers without a native catalog surface return an
     * empty list and callers fall back to seeded defaults.
     *
     * <p>May start or contact the provider process, so callers should cache
     * the result rather than calling on hot paths.
     *
     * @return current provider models, or empty when not enumerable
     * @throws AgentUnavailableException when the provider cannot be reached
     */
    default List<ProviderModel> models()
    {
        return List.of();
    }

    /**
     * Runs the provider's own self-updater, which installs a newer CLI only
     * when one is available. Blocking and potentially slow (it may download a
     * release), so callers run it off hot paths and re-probe afterwards.
     *
     * @return updater output, or empty when the driver has no updater
     * @throws AgentUnavailableException when the updater fails
     */
    default Optional<String> update()
    {
        return Optional.empty();
    }

    /**
     * Opens a new provider session or resumes the reference in the context.
     *
     * <p>The returned session is fixed to the supplied worktree and permission
     * policy. Events must be normalized, ordered, and free of secrets or hidden
     * reasoning before they are sent to the sink.
     *
     * @param context task, worktree, resources, policy, approval, and resume context
     * @param events sink for normalized provider events
     * @return open provider session
     * @throws AgentUnavailableException when the provider cannot open or resume
     */
    AgentSession open(AgentSessionContext context, AgentEventSink events);
}
