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
package com.bytequay.app.flow.upstream;

import com.bytequay.app.flow.timeline.TaskViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wiring for the read side the sync surfaces consume.
 *
 * <p>Separate from the synchronization composition root because its consumer
 * is an HTTP controller rather than the runtime: nothing in the run's own
 * execution path reads this projection, and nothing here reaches outside the
 * flow's own tables.
 */
@Configuration(proxyBeanMethods = false)
public class UpstreamSyncViewConfiguration
{
    @Bean
    public UpstreamSyncViews newFlowUpstreamSyncViews(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            UpstreamSync upstreamSync,
            TaskViews taskViews,
            ObjectMapper objectMapper)
    {
        return new UpstreamSyncViews(
                dataSource, upstreamSync, taskViews, objectMapper);
    }
}
