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
package com.bytequay.app.service;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.repository.CredentialStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end check of the default-per-provider behaviour the V84
 * migration introduces. Runs against the real Flyway-migrated SQLite
 * schema so the partial unique index is also exercised — a stray
 * "two defaults in one group" save would surface here.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestCredentialDefault
{
    @Autowired
    private CredentialStore store;
    @Autowired
    private CredentialService service;

    @Test
    void firstUpsertInAGroupBecomesTheDefault()
    {
        String name = uniqueName("openai");
        Credential row = service.upsert(
                CredentialType.AI, name, "personal", "sk-aaa", null, null);

        assertThat(row.isDefault()).isTrue();
        assertThat(service.getDefault(CredentialType.AI, name))
                .map(Credential::instanceName)
                .hasValue("personal");
    }

    @Test
    void secondInstanceDoesNotStealTheDefault()
    {
        String name = uniqueName("anthropic");
        service.upsert(CredentialType.AI, name, "personal", "sk-aaa", null, null);
        Credential second = service.upsert(
                CredentialType.AI, name, "work", "sk-bbb", null, null);

        assertThat(second.isDefault()).isFalse();
        assertThat(service.getDefault(CredentialType.AI, name))
                .map(Credential::instanceName)
                .hasValue("personal");
    }

    @Test
    void setDefaultPromotesAndClearsThePrior()
    {
        String name = uniqueName("deepseek");
        service.upsert(CredentialType.AI, name, "personal", "sk-aaa", null, null);
        service.upsert(CredentialType.AI, name, "work", "sk-bbb", null, null);

        Credential promoted = service.setDefault(CredentialType.AI, name, "work");
        assertThat(promoted.isDefault()).isTrue();

        // Single-default invariant holds.
        List<Credential> defaults = store.findByTypeAndName(CredentialType.AI, name).stream()
                .filter(Credential::isDefault)
                .toList();
        assertThat(defaults).extracting(Credential::instanceName).containsExactly("work");
    }

    @Test
    void getSecretWithoutInstanceFollowsTheDefault()
    {
        String name = uniqueName("openai-secret");
        service.upsert(CredentialType.AI, name, "personal", "sk-personal", null, null);
        service.upsert(CredentialType.AI, name, "work", "sk-work", null, null);

        // Default is the first one inserted — unnamed lookup honours it.
        assertThat(service.getSecret(CredentialType.AI, name)).hasValue("sk-personal");

        service.setDefault(CredentialType.AI, name, "work");
        // After the flip, the unnamed lookup resolves to "work".
        assertThat(service.getSecret(CredentialType.AI, name)).hasValue("sk-work");

        // Named lookup still wins regardless of which one is default.
        assertThat(service.getSecret(CredentialType.AI, name, "personal")).hasValue("sk-personal");
    }

    @Test
    void deletingTheDefaultPromotesASibling()
    {
        String name = uniqueName("anthropic-promotion");
        service.upsert(CredentialType.AI, name, "personal", "sk-aaa", null, null);
        service.upsert(CredentialType.AI, name, "work", "sk-bbb", null, null);

        // Personal is default; delete it.
        service.delete(CredentialType.AI, name, "personal");

        assertThat(service.getDefault(CredentialType.AI, name))
                .map(Credential::instanceName)
                .hasValue("work");
        assertThat(store.findByTypeAndName(CredentialType.AI, name).stream()
                .filter(Credential::isDefault)
                .toList())
                .hasSize(1);
    }

    @Test
    void setDefaultOnMissingInstanceThrows()
    {
        String name = uniqueName("missing");
        service.upsert(CredentialType.AI, name, "personal", "sk-aaa", null, null);

        // Spring wraps IllegalArgumentException from a JPA boundary in
        // InvalidDataAccessApiUsageException; match the message rather
        // than the exact type so the test is robust to that wrapping.
        assertThatThrownBy(() -> service.setDefault(CredentialType.AI, name, "nope"))
                .hasMessageContaining("no credential for AI");
    }

    /** Each test uses a fresh provider name so it doesn't collide
     *  with the V84-backfilled rows or other tests running in the same
     *  Spring context. */
    private static String uniqueName(String prefix)
    {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
