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

import com.bytequay.app.agent.AgentServingGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

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

    @Test
    void contextLoads()
    {
        assertThat(agentServingGate.isReady()).isTrue();
    }
}
