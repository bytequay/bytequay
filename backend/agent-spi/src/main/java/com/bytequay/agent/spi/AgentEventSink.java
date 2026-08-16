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

/**
 * Receives provider-neutral events from a driver for backend sequencing.
 *
 * <p>This is the provider-to-core event boundary. Drivers preserve observed
 * order and remove secrets, raw protocol payloads, and unsupported hidden
 * reasoning before emitting an event.
 */
@FunctionalInterface
public interface AgentEventSink
{
    /**
     * Accepts one provider-normalized event for backend sequencing.
     *
     * <p>The driver must call the sink in provider order and must exclude secrets,
     * raw reasoning, and unsupported provider payloads. Raw Codex JSON-RPC and
     * Claude {@code stream-json} never cross this boundary; each driver maps its
     * wire format to {@link ProviderAgentEvent} first.
     *
     * @param event normalized event associated with the current task and turn
     */
    void emit(ProviderAgentEvent event);
}
