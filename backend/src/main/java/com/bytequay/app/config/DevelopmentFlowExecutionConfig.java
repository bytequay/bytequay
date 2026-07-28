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
import java.util.Map;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.CLEANUP;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.GITHUB;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.MERGE;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.REMOTE_OBSERVATION;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.REVIEW;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;

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
            int defaultTrunkLimit,
            @Value("${bytequay.development-flow.capacity.validation:4}") int validationLimit,
            @Value("${bytequay.development-flow.capacity.review:6}") int reviewLimit,
            @Value("${bytequay.development-flow.capacity.local-git:4}") int localGitLimit,
            @Value("${bytequay.development-flow.capacity.github:6}") int githubLimit,
            @Value("${bytequay.development-flow.capacity.remote-observation:8}")
            int remoteObservationLimit,
            @Value("${bytequay.development-flow.capacity.merge:2}") int mergeLimit,
            @Value("${bytequay.development-flow.capacity.cleanup:4}") int cleanupLimit)
    {
        return new DevelopmentFlowCapacityPolicySource(
                threads,
                settings,
                defaultWorkspaceLimit,
                defaultTrunkLimit,
                Map.of(
                        VALIDATION, validationLimit,
                        REVIEW, reviewLimit,
                        LOCAL_GIT, localGitLimit,
                        GITHUB, githubLimit,
                        REMOTE_OBSERVATION, remoteObservationLimit,
                        MERGE, mergeLimit,
                        CLEANUP, cleanupLimit));
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
