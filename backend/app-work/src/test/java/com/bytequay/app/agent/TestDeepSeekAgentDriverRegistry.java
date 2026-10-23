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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDeepSeekAgentDriverRegistry
{
    private static final Instant CREATED_AT = Instant.parse("2026-08-23T10:00:00Z");

    @Test
    void removingAndReaddingAnAliasNeverRebindsTheOldLane()
    {
        ConcurrentMap<String, ModelApiKey> saved = new ConcurrentHashMap<>();
        ModelApiKeyStore keys = keyStore(saved);
        AgentDriverRegistry cli = mock(AgentDriverRegistry.class);
        AgentDriver cliDriver = mock(AgentDriver.class);
        when(cli.require(AgentLaneId.CODEX_CLI)).thenReturn(cliDriver);
        AgentProviderStatus cliStatus = status(AgentLaneId.CODEX_CLI);
        when(cli.statuses()).thenReturn(ImmutableList.of(cliStatus));
        AtomicInteger created = new AtomicInteger();
        DeepSeekAgentDriverRegistry registry = new DeepSeekAgentDriverRegistry(cli, keys, key -> {
            created.incrementAndGet();
            return readyDriver(lane(key));
        });

        assertThat(registry.require(AgentLaneId.CODEX_CLI)).isSameAs(cliDriver);
        ModelApiKey original = key("personal");
        saved.put(original.id(), original);
        AgentLaneId oldLane = lane(original);
        AgentDriver oldDriver = registry.require(oldLane);
        assertThat(registry.statuses()).extracting(AgentProviderStatus::laneId).containsExactly(AgentLaneId.CODEX_CLI, oldLane);

        saved.remove(original.id());
        AgentProviderUnavailableException removed = catchThrowableOfType(() -> registry.require(oldLane), AgentProviderUnavailableException.class);
        assertThat(removed.status().readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(removed.status().diagnostic()).contains("removed");

        ModelApiKey replacement = key("personal");
        saved.put(replacement.id(), replacement);
        AgentLaneId newLane = lane(replacement);
        assertThat(newLane).isNotEqualTo(oldLane);
        assertThat(registry.require(newLane)).isNotSameAs(oldDriver);
        assertThat(catchThrowableOfType(() -> registry.require(oldLane), AgentProviderUnavailableException.class).status().laneId()).isEqualTo(oldLane);
        assertThat(registry.statuses()).extracting(AgentProviderStatus::laneId).containsExactly(AgentLaneId.CODEX_CLI, newLane);
        assertThat(created).hasValue(2);
    }

    @Test
    void concurrentLookupsCreateOneDriverForAKey()
            throws Exception
    {
        ModelApiKey key = key("shared");
        ConcurrentMap<String, ModelApiKey> saved = new ConcurrentHashMap<>();
        saved.put(key.id(), key);
        AtomicInteger created = new AtomicInteger();
        DeepSeekAgentDriverRegistry registry = new DeepSeekAgentDriverRegistry(mock(AgentDriverRegistry.class), keyStore(saved), found -> {
            created.incrementAndGet();
            return readyDriver(lane(found));
        });

        CountDownLatch start = new CountDownLatch(1);
        ImmutableList.Builder<Future<AgentDriver>> results = ImmutableList.builder();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 16; i++) {
                results.add(workers.submit(() -> {
                    start.await();
                    return registry.require(lane(key));
                }));
            }
            start.countDown();
            ImmutableList<Future<AgentDriver>> futures = results.build();
            AgentDriver first = futures.getFirst().get();
            for (Future<AgentDriver> result : futures) {
                assertThat(result.get()).isSameAs(first);
            }
        }
        assertThat(created).hasValue(1);
    }

    private static ModelApiKeyStore keyStore(ConcurrentMap<String, ModelApiKey> saved)
    {
        ModelApiKeyStore keys = mock(ModelApiKeyStore.class);
        when(keys.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(saved.get(invocation.getArgument(0))));
        when(keys.findAll()).thenAnswer(ignored -> ImmutableList.copyOf(saved.values()));
        return keys;
    }

    private static ModelApiKey key(String alias)
    {
        return new ModelApiKey(alias, "DeepSeek", "masked", CREATED_AT);
    }

    private static AgentLaneId lane(ModelApiKey key)
    {
        return new AgentLaneId(DeepSeekAgentDriverRegistry.PREFIX + key.id());
    }

    private static AgentDriver readyDriver(AgentLaneId lane)
    {
        AgentDriver driver = mock(AgentDriver.class);
        when(driver.probe()).thenReturn(status(lane));
        return driver;
    }

    private static AgentProviderStatus status(AgentLaneId lane)
    {
        return new AgentProviderStatus(lane, AgentProviderStatus.Readiness.READY, Optional.empty(), "ready", AgentCapabilities.none());
    }
}
