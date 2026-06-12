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
package com.bytequay.app.service.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Wires the shared {@link TurnRunner} as a singleton bean so the
 * review-pass compositions (lead + seats) inject the same loop the
 * thread agent composes. The thread agent constructs its own instance
 * (it predates the bean and owns its client lifecycle); both run the
 * identical code.
 */
@Configuration
public class AgentsConfig
{
    @Bean
    public TurnRunner turnRunner(ObjectMapper mapper)
    {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        return new TurnRunner(client, mapper);
    }
}
