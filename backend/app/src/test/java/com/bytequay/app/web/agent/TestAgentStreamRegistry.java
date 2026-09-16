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
package com.bytequay.app.web.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentStreamRegistry
{
    @Test
    void stopEndsOpenStreamsBeforeGracefulShutdown()
            throws Exception
    {
        AgentStreamRegistry streams = new AgentStreamRegistry();
        streams.start();
        AtomicBoolean cancelled = new AtomicBoolean();
        streams.open(subscriber -> subscriber.onSubscribe(new Flow.Subscription()
        {
            @Override
            public void request(long count) {}

            @Override
            public void cancel()
            {
                cancelled.set(true);
            }
        }));

        streams.stop();

        assertThat(cancelled).isTrue();
        assertThat(streams.getPhase()).isGreaterThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
    }
}
