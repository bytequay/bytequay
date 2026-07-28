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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowExecutionConfig
{
    @Test
    void v2DispatcherIsAbsentByDefault()
    {
        new ApplicationContextRunner()
                .withUserConfiguration(DevelopmentFlowExecutionConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CapacityManager.class);
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
                        ExecutionPorts.ResultDeliveryPort.class,
                        CapacityManager.CapacityPolicySource.class);
    }
}
