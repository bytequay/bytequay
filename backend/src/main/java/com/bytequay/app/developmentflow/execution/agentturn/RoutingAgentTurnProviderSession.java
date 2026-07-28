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
package com.bytequay.app.developmentflow.execution.agentturn;

import java.util.EnumMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Selects the one provider adapter named by a Turn's frozen transport. */
public final class RoutingAgentTurnProviderSession
        implements AgentTurnProviderSession
{
    private final Map<Transport, AgentTurnProviderSession> providers;

    public RoutingAgentTurnProviderSession(
            AgentTurnProviderSession cli,
            AgentTurnProviderSession api)
    {
        requireNonNull(cli, "cli is null");
        requireNonNull(api, "api is null");
        EnumMap<Transport, AgentTurnProviderSession> routes =
                new EnumMap<>(Transport.class);
        routes.put(Transport.CLI, cli);
        routes.put(Transport.API, api);
        providers = Map.copyOf(routes);
    }

    @Override
    public Session open(Request request, Observer observer)
            throws Exception
    {
        requireNonNull(request, "request is null");
        requireNonNull(observer, "observer is null");
        return providers.get(request.transport()).open(request, observer);
    }
}
