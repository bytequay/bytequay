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
package com.bytequay.app.agent;

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.core.store.ModelApiKeyStore;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Resolves live DeepSeek key lanes alongside the installed CLI lanes. */
final class DeepSeekAgentDriverRegistry
        implements AgentDriverRegistry
{
    static final String PREFIX = "deepseek-key-";

    private final AgentDriverRegistry cliDrivers;
    private final ModelApiKeyStore keys;
    private final Function<ModelApiKey, AgentDriver> createDriver;
    private final ConcurrentMap<String, AgentDriver> keyDrivers = new ConcurrentHashMap<>();

    DeepSeekAgentDriverRegistry(AgentDriverRegistry cliDrivers, ModelApiKeyStore keys, Function<ModelApiKey, AgentDriver> createDriver)
    {
        this.cliDrivers = requireNonNull(cliDrivers, "cliDrivers is null");
        this.keys = requireNonNull(keys, "keys is null");
        this.createDriver = requireNonNull(createDriver, "createDriver is null");
    }

    @Override
    public AgentDriver require(AgentLaneId laneId)
    {
        requireNonNull(laneId, "laneId is null");
        if (!laneId.value().startsWith(PREFIX)) {
            return cliDrivers.require(laneId);
        }
        String id = laneId.value().substring(PREFIX.length());
        ModelApiKey key = keys.findById(id)
                .filter(found -> found.provider().equals("DeepSeek"))
                .orElseThrow(() -> new AgentProviderUnavailableException(removed(laneId)));
        return keyDrivers.computeIfAbsent(key.id(), ignored -> createDriver.apply(key));
    }

    @Override
    public void checkWorkspaceAccess(AgentLaneId laneId, Optional<String> workspaceId)
    {
        cliDrivers.checkWorkspaceAccess(laneId, workspaceId);
    }

    @Override
    public List<AgentProviderStatus> statuses()
    {
        ImmutableList.Builder<AgentProviderStatus> result = ImmutableList.builder();
        result.addAll(cliDrivers.statuses());
        for (ModelApiKey key : keys.findAll()) {
            if (!key.provider().equals("DeepSeek")) {
                continue;
            }
            AgentLaneId laneId = new AgentLaneId(PREFIX + key.id());
            try {
                result.add(require(laneId).probe());
            }
            catch (RuntimeException failure) {
                // Provider errors can include HTTP response bodies; never echo them into settings.
                result.add(new AgentProviderStatus(laneId,
                        AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                        Optional.empty(),
                        "DeepSeek key lane could not be checked. Try again.",
                        AgentCapabilities.none()));
            }
        }
        return result.build();
    }

    private static AgentProviderStatus removed(AgentLaneId laneId)
    {
        return new AgentProviderStatus(laneId,
                AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                Optional.empty(),
                "DeepSeek key removed. Add a new key for a new task; this task keeps its original key lane.",
                AgentCapabilities.none());
    }
}
