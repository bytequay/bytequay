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
package com.bytequay.app.developmentflow.compatibility;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowCanaryRoute
{
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RouteConfig.class);

    @Test
    void everyNonblankWorkspaceRoutesToV2()
    {
        context.run(result -> {
            DevelopmentFlowCanaryRoute route =
                    result.getBean(DevelopmentFlowCanaryRoute.class);
            assertThat(route.routesNewTaskToV2("workspace-1")).isTrue();
            assertThat(route.routesNewTaskToV2(" ")).isFalse();
            assertThat(route.routesNewTaskToV2(null)).isFalse();
            assertThat(route.snapshot().v2Only()).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DevelopmentFlowCanaryRoute.class)
    static class RouteConfig {}
}
