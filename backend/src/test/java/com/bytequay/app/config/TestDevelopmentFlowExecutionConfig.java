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
package com.bytequay.app.config;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.LegacyCapacityBridge;
import com.bytequay.app.developmentflow.execution.LegacyCapacityLeaseMaintainer;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadSettings;
import com.bytequay.app.repository.ThreadSettingsStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDevelopmentFlowExecutionConfig
{
    @Test
    void sharedCapacityIsAlwaysOnButV2DispatcherIsAbsentByDefault()
    {
        contextRunner()
                .withUserConfiguration(DevelopmentFlowExecutionConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CapacityManager.class);
                    assertThat(context).hasSingleBean(LegacyCapacityBridge.class);
                    assertThat(context).hasSingleBean(LegacyCapacityLeaseMaintainer.class);
                    assertThat(context).doesNotHaveBean(ExecutionDispatcher.class);
                });
    }

    @Test
    void enablingRequiresRealPolicyHandlersAndDeliveryPorts()
    {
        Method dispatcher = Arrays.stream(
                        DevelopmentFlowExecutionConfig.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == ExecutionDispatcher.class)
                .findFirst()
                .orElseThrow();
        assertThat(dispatcher.getParameterTypes()).contains(
                ExecutionPorts.OperationHandlerRegistry.class,
                ExecutionPorts.ResultDeliveryPort.class,
                ExecutionPorts.ExecutionEvidencePort.class,
                ExecutionPorts.DispatchTicketStore.class);
        assertThat(Arrays.stream(
                        DevelopmentFlowExecutionConfig.class.getDeclaredMethods())
                .map(Method::getReturnType))
                .doesNotContain(
                        ExecutionPorts.OperationHandlerRegistry.class,
                        ExecutionPorts.ResultDeliveryPort.class);
    }

    @Test
    void policyProjectsExplicitSettingsAndMigratedParallelSlots()
    {
        ThreadStore threads = mock(ThreadStore.class);
        ThreadSettingsStore settings = mock(ThreadSettingsStore.class);
        Thread trunk = mock(Thread.class);
        when(threads.findThreadById("trunk")).thenReturn(Optional.of(trunk));
        when(trunk.parallelSlots()).thenReturn(3);
        DevelopmentFlowCapacityPolicySource source =
                new DevelopmentFlowCapacityPolicySource(
                        threads, settings, 4, 4,
                        Map.of(CapacityManager.CapacityLane.VALIDATION, 3));
        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                "legacy",
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(CapacityManager.CapacityLane.CLI),
                new CapacityManager.CapacityScope("workspace", "trunk", null, null),
                true,
                false,
                false);

        assertThat(source.current(request).trunkLimit("trunk")).isEqualTo(3);

        when(settings.find("trunk")).thenReturn(Optional.of(new ThreadSettings(
                "trunk", 2, null, null, null, Instant.EPOCH)));
        assertThat(source.current(request).trunkLimit("trunk")).isEqualTo(2);
        assertThat(source.current(request).laneLimits())
                .containsEntry(CapacityManager.CapacityLane.CLI, 4)
                .containsEntry(CapacityManager.CapacityLane.API, 6)
                .containsEntry(CapacityManager.CapacityLane.VALIDATION, 3);
    }

    @Test
    void everyNonAgentLaneHasAnExplicitConfigurableLimit()
    {
        contextRunner()
                .withPropertyValues(
                        "bytequay.development-flow.capacity.validation=11",
                        "bytequay.development-flow.capacity.review=12",
                        "bytequay.development-flow.capacity.local-git=13",
                        "bytequay.development-flow.capacity.github=14",
                        "bytequay.development-flow.capacity.remote-observation=15",
                        "bytequay.development-flow.capacity.merge=16",
                        "bytequay.development-flow.capacity.cleanup=17")
                .withUserConfiguration(DevelopmentFlowExecutionConfig.class)
                .run(context -> {
                    CapacityManager.CapacityPolicy policy = context.getBean(
                                    CapacityManager.CapacityPolicySource.class)
                            .current();
                    assertThat(policy.laneLimits()).containsExactlyInAnyOrderEntriesOf(Map.of(
                            CapacityManager.CapacityLane.CLI, 4,
                            CapacityManager.CapacityLane.API, 6,
                            CapacityManager.CapacityLane.VALIDATION, 11,
                            CapacityManager.CapacityLane.REVIEW, 12,
                            CapacityManager.CapacityLane.LOCAL_GIT, 13,
                            CapacityManager.CapacityLane.GITHUB, 14,
                            CapacityManager.CapacityLane.REMOTE_OBSERVATION, 15,
                            CapacityManager.CapacityLane.MERGE, 16,
                            CapacityManager.CapacityLane.CLEANUP, 17));
                    assertThat(policy.reservedTrunkControl()).containsExactlyInAnyOrderEntriesOf(
                            Map.of(
                                    CapacityManager.CapacityLane.CLI, 1,
                                    CapacityManager.CapacityLane.API, 1));
                });
    }

    private static ApplicationContextRunner contextRunner()
    {
        return new ApplicationContextRunner()
                .withBean(ThreadStore.class, () -> mock(ThreadStore.class))
                .withBean(ThreadSettingsStore.class, () -> mock(ThreadSettingsStore.class))
                .withBean(
                        CapacityManager.CapacityLeaseStore.class,
                        () -> mock(CapacityManager.CapacityLeaseStore.class));
    }
}
