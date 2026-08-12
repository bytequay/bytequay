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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.service.agents.TurnRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import java.time.Clock;

/**
 * Composition for upstream cherry-pick synchronization only.
 *
 * <p>Separate from the runtime's own root because this is an optional
 * pre-publication producer: a deployment without it runs every ordinary Task
 * unchanged, and the INITIAL lane simply never has a replacement body
 * installed.
 */
@Configuration(proxyBeanMethods = false)
public class UpstreamSyncConfiguration
{
    @Bean
    public UpstreamSync newFlowUpstreamSync(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            ObjectMapper objectMapper,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new UpstreamSync(dataSource, objectMapper, clock);
    }

    @Bean
    public UpstreamSyncCoordinator newFlowUpstreamSyncCoordinator(
            FlowRuntime runtime,
            UpstreamSync upstreamSync,
            NewFlowAgentLaunches launches,
            TurnRunner turnRunner,
            ObjectMapper objectMapper)
    {
        return new UpstreamSyncCoordinator(
                runtime, upstreamSync, launches, turnRunner, objectMapper);
    }

    @Bean
    public UpstreamSyncCommands newFlowUpstreamSyncCommands(
            TaskProvisioning provisioning,
            UpstreamSync upstreamSync,
            NewFlowDispatcher dispatcher,
            InitialTaskDispatcher initialTasks)
    {
        return new UpstreamSyncCommands(
                provisioning, upstreamSync, dispatcher, initialTasks);
    }
}
