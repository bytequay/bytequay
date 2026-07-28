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
import com.bytequay.app.repository.ThreadSettingsStore;
import com.bytequay.app.repository.ThreadStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Shared capacity wiring is always active during LEGACY/V2 coexistence.
 * Enabling V2 dispatch still requires real handlers and delivery ports.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentFlowExecutionConfig
{
    @Bean
    @ConditionalOnMissingBean(CapacityManager.CapacityPolicySource.class)
    public CapacityManager.CapacityPolicySource developmentFlowCapacityPolicy(
            ThreadStore threads,
            ThreadSettingsStore settings,
            @Value("${bytequay.development-flow.capacity.default-workspace-running-tasks:4}")
            int defaultWorkspaceLimit,
            @Value("${bytequay.development-flow.capacity.default-trunk-running-tasks:4}")
            int defaultTrunkLimit)
    {
        return new DevelopmentFlowCapacityPolicySource(
                threads, settings, defaultWorkspaceLimit, defaultTrunkLimit);
    }

    @Bean
    @ConditionalOnMissingBean(CapacityManager.class)
    public CapacityManager capacityManager(
            CapacityManager.CapacityLeaseStore leases,
            CapacityManager.CapacityPolicySource policies)
    {
        return new CapacityManager(
                leases, policies, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    @Bean
    @ConditionalOnMissingBean(LegacyCapacityBridge.class)
    public LegacyCapacityBridge legacyCapacityBridge(CapacityManager capacityManager)
    {
        return new LegacyCapacityBridge(capacityManager);
    }

    @Bean
    @ConditionalOnMissingBean(LegacyCapacityLeaseMaintainer.class)
    public LegacyCapacityLeaseMaintainer legacyCapacityLeaseMaintainer(
            LegacyCapacityBridge bridge)
    {
        return new LegacyCapacityLeaseMaintainer(bridge);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public ExecutionDispatcher v2ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence)
    {
        return new ExecutionDispatcher(
                capacityManager,
                tickets,
                handlers,
                resultDelivery,
                evidence,
                Clock.systemUTC(),
                new ExecutionDispatcher.Config(
                        "v2-dispatcher",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(5),
                        3,
                        100));
    }
}
