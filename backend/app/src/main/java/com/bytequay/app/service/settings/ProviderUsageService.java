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
package com.bytequay.app.service.settings;

import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.ProviderQuotaSnapshot;
import com.bytequay.agent.spi.ProviderQuotaWindow;
import com.bytequay.core.store.ModelApiKeyStore;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Service
public class ProviderUsageService
{
    private static final String API_KEY_PREFIX = "api-key-";

    private final AgentDriverRegistry drivers;
    private final ClaudeAccounts claudeAccounts;
    private final ModelApiKeyStore apiKeys;
    private final Clock clock;
    private final Cache<AgentLaneId, Usage> cache = CacheBuilder.newBuilder().maximumSize(100).expireAfterWrite(Duration.ofSeconds(60)).build();

    public ProviderUsageService(AgentDriverRegistry drivers, ClaudeAccounts claudeAccounts, ModelApiKeyStore apiKeys, Clock clock)
    {
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.claudeAccounts = requireNonNull(claudeAccounts, "claudeAccounts is null");
        this.apiKeys = requireNonNull(apiKeys, "apiKeys is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public List<Account> accounts()
    {
        ImmutableList.Builder<Account> result = ImmutableList.builder();
        result.add(new Account("codex", "Codex"), new Account("claude", "Claude"));
        claudeAccounts.list().forEach(account -> result.add(new Account(account.laneId().value(), "Claude · " + account.label())));
        apiKeys.findAll().forEach(key -> result.add(new Account(API_KEY_PREFIX + key.id(), key.provider() + " · " + key.alias())));
        return result.build();
    }

    public Usage usage(String accountId)
    {
        checkArgument(accounts().stream().anyMatch(account -> account.id().equals(accountId)), "Unknown usage account");
        if (accountId.startsWith(API_KEY_PREFIX)) {
            // API-key accounts have no subscription quota reader. Never query a CLI login's limits on their behalf.
            return new Usage(accountId, Optional.empty(), ImmutableList.of(), clock.instant(),
                    Optional.of("Usage limits aren’t available for this API-key account. Check your provider’s billing dashboard."));
        }
        AgentLaneId lane = new AgentLaneId(accountId);
        try {
            // Resolve before consulting the cache so a removed/disabled profile cannot keep serving a previous account's data.
            AgentDriver driver = drivers.require(lane);
            return cache.get(lane, () -> load(driver));
        }
        catch (ExecutionException | RuntimeException failure) {
            return unavailable(accountId);
        }
    }

    private Usage load(AgentDriver driver)
    {
        try {
            ProviderQuotaSnapshot quota = driver.quota();
            Optional<String> message = quota.message();
            if (quota.windows().isEmpty() && message.isEmpty()) {
                message = Optional.of("This account does not expose usage limits.");
            }
            return new Usage(driver.id().value(), quota.plan(), quota.windows(), clock.instant(), message);
        }
        catch (RuntimeException failure) {
            // Cache failures too: reopening the popover must not hammer an unavailable provider or return its raw diagnostics.
            return unavailable(driver.id().value());
        }
    }

    private Usage unavailable(String accountId)
    {
        return new Usage(accountId, Optional.empty(), ImmutableList.of(), clock.instant(), Optional.of("Usage unavailable. Check this account in Settings and try again."));
    }

    public record Account(String id, String label) {}

    public record Usage(String accountId, Optional<String> plan, List<ProviderQuotaWindow> windows, Instant checkedAt, Optional<String> message)
    {
        public Usage
        {
            windows = ImmutableList.copyOf(windows);
        }
    }
}
