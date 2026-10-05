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

    public synchronized ClaudeAccount add(String label, String email)
    {
        ClaudeAccount account = new ClaudeAccount(UUID.randomUUID().toString(), label, Optional.of(requireNonNull(email, "email is null")));
        List<ClaudeAccount> existing = list();
        checkArgument(existing.stream().noneMatch(value -> value.label().equalsIgnoreCase(account.label())), "A Claude account with that name already exists");
        save(ImmutableList.<ClaudeAccount>builder().addAll(existing).add(account).build());
        return account;
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
