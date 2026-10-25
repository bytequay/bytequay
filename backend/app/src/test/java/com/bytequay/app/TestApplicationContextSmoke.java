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
package com.bytequay.app;

import com.bytequay.app.agent.runtime.AgentProperties;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.config.WriteVersion;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the entire Spring application context — every controller, every
 * service and every JPA repository, and asserts
 * that the bean graph wires up without errors. Catches DI / config
 * regressions that the slice-only {@code @WebMvcTest} suites miss
 * (missing constructor args, broken Qualifiers, or beans that fail their
 * own validation in @PostConstruct).
 *
 * <p>The test also verifies that startup recovery opened the agent-serving
 * gate before the context became ready.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestApplicationContextSmoke
{
    @Autowired
    private AgentServingGate agentServingGate;

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private WriteVersion writeVersion;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Test
    void contextLoads()
    {
        assertThat(agentServingGate.isReady()).isTrue();
        assertThat(agentProperties.codexMinimumVersion()).isEqualTo("0.164.0");
    }

    @Test
    void writeVersionChangesOnlyAfterACommittedWrite()
    {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String before = writeVersion.current();

        transaction.executeWithoutResult(ignored -> entityManager.createNativeQuery("SELECT COUNT(*) FROM app_settings").getSingleResult());
        assertThat(writeVersion.current()).isEqualTo(before);
        transaction.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM pull_requests WHERE 1 = 0").executeUpdate();
            status.setRollbackOnly();
        });
        assertThat(writeVersion.current()).isEqualTo(before);
        // Sync checkpoints feed no versioned read.
        transaction.executeWithoutResult(ignored -> entityManager.createNativeQuery("DELETE FROM app_settings WHERE 1 = 0").executeUpdate());
        assertThat(writeVersion.current()).isEqualTo(before);
        transaction.executeWithoutResult(ignored -> entityManager.createNativeQuery("DELETE FROM pull_requests WHERE 1 = 0").executeUpdate());
        assertThat(writeVersion.current()).isNotEqualTo(before);
    }
}
