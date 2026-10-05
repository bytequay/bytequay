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

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClaudeAccounts
{
    @Test
    void removalPersistsAndReAddingTheNameNeverReusesTheOldLogin()
    {
        InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
        ClaudeAccounts accounts = new ClaudeAccounts(store, new ObjectMapper());
        ClaudeAccount work = accounts.add("Work", "work@example.com");
        ClaudeAccount personal = accounts.add("Personal", "personal@example.com");
        accounts.remove(work.id());
        accounts.remove(work.id());
        assertThat(new ClaudeAccounts(store, new ObjectMapper()).list()).containsExactly(personal);
        assertThat(accounts.add("Work", "work@example.com").id()).isNotEqualTo(work.id());
        assertThatThrownBy(() -> accounts.remove("claude")).isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.list()).hasSize(2);
    }

    @Test
    void preservesSeparateIdentitiesAcrossRestartAndRejectsAmbiguousNames()
    {
        InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
        ClaudeAccounts accounts = new ClaudeAccounts(store, new ObjectMapper());
        ClaudeAccount work = accounts.add(" Work ", " work@example.com ");
        ClaudeAccount personal = accounts.add("me@example.com", "personal@example.com");
        assertThat(work.label()).isEqualTo("Work");
        assertThat(work.email()).contains("work@example.com");
        assertThat(work.laneId()).isNotEqualTo(personal.laneId());
        assertThat(new ClaudeAccounts(store, new ObjectMapper()).list()).containsExactly(work, personal);
        assertThatThrownBy(() -> accounts.add("work", "work@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.add(" ", "work@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.add("line\nbreak", "work@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.add("x".repeat(81), "work@example.com")).isInstanceOf(IllegalArgumentException.class);
        for (String email : new String[] {"", "wrong", "one@@example.com", "a b@example.com", "a\nb@example.com", "x".repeat(243) + "@example.com"}) {
            assertThatThrownBy(() -> accounts.add("Invalid email", email)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ClaudeAccount("../../other-home", "Invalid", Optional.of("work@example.com"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.list()).containsExactly(work, personal);
        store.put("model.claudeAccounts", "broken");
        assertThatThrownBy(() -> accounts.add("New", "work@example.com")).isInstanceOf(IllegalStateException.class);
        assertThat(store.get("model.claudeAccounts")).contains("broken");
    }
}
