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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Inert V2 execution wiring. Enabling it deliberately requires real policy,
 * operation-handler, and result-delivery beans from a later routing slice.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "bytequay.development-flow.v2-dispatch-enabled",
        havingValue = "true")
public class DevelopmentFlowExecutionConfig
{
    @Bean
    public CapacityManager v2CapacityManager(
            CapacityManager.CapacityLeaseStore leases,
            CapacityManager.CapacityPolicySource policies)
    {
        return new CapacityManager(
                leases, policies, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
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
