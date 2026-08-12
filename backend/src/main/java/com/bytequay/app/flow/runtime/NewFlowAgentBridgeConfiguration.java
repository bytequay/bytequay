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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the one new-flow component the web layer consumes.
 *
 * <p>Separate from the runtime's main composition root because its consumers
 * are: the agent body registers a run's tools here, and an HTTP controller
 * reads them back. Everything in that root is internal to the runtime and
 * reachable only from it.
 *
 * <p>The bean is a singleton because that is the requirement, not a
 * convenience: a subprocess reaching in over loopback must find the same
 * registry the body wrote to, and two instances would present an agent with an
 * endpoint that has no tools.
 */
@Configuration(proxyBeanMethods = false)
public class NewFlowAgentBridgeConfiguration
{
    @Bean
    public NewFlowAgentToolBridge newFlowAgentToolBridge(ObjectMapper objectMapper)
    {
        return new NewFlowAgentToolBridge(objectMapper);
    }
}
