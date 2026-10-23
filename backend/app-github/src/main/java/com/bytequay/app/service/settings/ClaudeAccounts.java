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

import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Stores account names, login emails, and stable identities in SQLite, never credentials. */
@Service
public class ClaudeAccounts
{
    private static final String KEY = "model.claudeAccounts";

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public ClaudeAccounts(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy().registerModule(new Jdk8Module());
    }

    public List<ClaudeAccount> list()
    {
        return settings.get(KEY).map(json -> {
            try {
                return ImmutableList.copyOf(mapper.readValue(json, ClaudeAccount[].class));
            }
            catch (JsonProcessingException failure) {
                // Losing an account ID would strand its resumable tasks; never overwrite a broken list.
                throw new IllegalStateException("Claude account settings could not be read", failure);
            }
        }).orElse(ImmutableList.of());
    }

    public synchronized ClaudeAccount add(String label, String email, Optional<String> workspaceId)
    {
        ClaudeAccount account = new ClaudeAccount(UUID.randomUUID().toString(), label, Optional.of(requireNonNull(email, "email is null")), workspaceId);
        List<ClaudeAccount> existing = list();
        checkArgument(existing.stream().noneMatch(value -> value.label().equalsIgnoreCase(account.label())), "A Claude account with that name already exists");
        save(ImmutableList.<ClaudeAccount>builder().addAll(existing).add(account).build());
        return account;
    }

    public Optional<ClaudeAccount> find(String laneId)
    {
        return list().stream().filter(account -> account.laneId().value().equals(laneId)).findFirst();
    }

    public boolean availableIn(String laneId, Optional<String> workspaceId)
    {
        return !laneId.startsWith(AgentLaneId.CLAUDE_ACCOUNT_PREFIX)
                || find(laneId).filter(account -> account.availableIn(workspaceId)).isPresent();
    }

    public void checkWorkspaceAccess(String laneId, Optional<String> workspaceId)
    {
        if (!availableIn(laneId, workspaceId)) {
            throw new AgentUnavailableException("Claude account unavailable in this repository");
        }
    }

    /** Restrict an existing global profile without changing its login identity. */
    public synchronized void restrictToWorkspace(String laneId, String workspaceId)
    {
        ClaudeAccount account = find(laneId).orElseThrow(() -> new IllegalArgumentException("Unknown Claude account"));
        checkArgument(account.workspaceId().isEmpty(), "This account already belongs to a repository");
        ClaudeAccount scoped = new ClaudeAccount(account.id(), account.label(), account.email(), Optional.of(workspaceId));
        save(list().stream().map(value -> value.id().equals(account.id()) ? scoped : value).collect(toImmutableList()));
    }

    /** Removes the saved choice, retaining CLI-owned login files and sessions already in flight. */
    public synchronized void remove(String id)
    {
        checkArgument(UUID.fromString(requireNonNull(id, "id is null")).toString().equals(id), "invalid Claude account ID");
        save(list().stream().filter(account -> !account.id().equals(id)).collect(toImmutableList()));
    }

    private void save(List<ClaudeAccount> accounts)
    {
        try {
            settings.put(KEY, mapper.writeValueAsString(accounts));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Claude account settings could not be saved", failure);
        }
    }
}
