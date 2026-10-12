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

import com.bytequay.agent.cli.claude.ClaudeCliDriver;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.settings.ClaudeAccount;
import com.bytequay.app.service.settings.ClaudeAccounts;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Each named login owns a driver, environment, and provider session namespace. */
final class ClaudeAccountDriverRegistry
        implements AgentDriverRegistry, AutoCloseable
{
    private final AgentDriverRegistry cliDrivers;
    private final ClaudeAccounts accounts;
    private final boolean enabled;
    private final Function<ClaudeAccount, ClaudeCliDriver> createDriver;
    private final ConcurrentMap<AgentLaneId, ClaudeCliDriver> accountDrivers = new ConcurrentHashMap<>();

    ClaudeAccountDriverRegistry(AgentDriverRegistry cliDrivers, ClaudeAccounts accounts, boolean enabled, Function<ClaudeAccount, ClaudeCliDriver> createDriver)
    {
        this.cliDrivers = requireNonNull(cliDrivers, "cliDrivers is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.enabled = enabled;
        this.createDriver = requireNonNull(createDriver, "createDriver is null");
    }

    @Override
    public AgentDriver require(AgentLaneId laneId)
    {
        if (!laneId.value().startsWith(AgentLaneId.CLAUDE_ACCOUNT_PREFIX)) {
            return cliDrivers.require(laneId);
        }
        if (!enabled) {
            throw new AgentUnavailableException("Claude Code is disabled");
        }
        ClaudeAccount account = accounts.list().stream().filter(value -> value.laneId().equals(laneId)).findFirst()
                .orElseThrow(() -> new AgentUnavailableException("This Claude account was removed or is unavailable; select an existing account for a new task"));
        return accountDrivers.computeIfAbsent(laneId, ignored -> createDriver.apply(account));
    }

    @Override
    public void checkWorkspaceAccess(AgentLaneId laneId, Optional<String> workspaceId)
    {
        accounts.checkWorkspaceAccess(laneId.value(), workspaceId);
    }

    @Override
    public List<AgentProviderStatus> statuses()
    {
        ImmutableList.Builder<AgentProviderStatus> result = ImmutableList.builder();
        result.addAll(cliDrivers.statuses());
        if (enabled) {
            for (ClaudeAccount account : accounts.list()) {
                try {
                    result.add(require(account.laneId()).probe());
                }
                catch (RuntimeException failure) {
                    result.add(new AgentProviderStatus(account.laneId(), AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                            Optional.empty(), "Claude account could not be checked. Try again.", AgentCapabilities.none()));
                }
            }
        }
        return result.build();
    }

    @Override
    public void close()
    {
        accountDrivers.values().forEach(ClaudeCliDriver::close);
    }
}
