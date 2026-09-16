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

import com.bytequay.agent.core.AgentEventEnvelope;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * Opens task and review event streams, and ends the open ones before the web server shuts down.
 *
 * <p>Graceful shutdown waits for every in-progress request, and an idle stream stays one until its task ends, so an
 * open task or review screen otherwise held shutdown for its whole timeout. The desktop app reads the clean end as a
 * drop and reconnects once the backend is back.
 */
@Component
public final class AgentStreamRegistry
        implements SmartLifecycle
{
    private final Set<AgentSseSubscriber> streams = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public SseEmitter open(Flow.Publisher<AgentEventEnvelope> events)
            throws IOException
    {
        SseEmitter emitter = new SseEmitter(0L);
        // An idle task may have no event to send for minutes, and until something is written the
        // response is never committed, so a client cannot tell an open stream from a hanging request.
        emitter.send(SseEmitter.event().comment("connected"));
        AgentSseSubscriber subscriber = new AgentSseSubscriber(emitter);
        events.subscribe(subscriber);
        streams.add(subscriber);
        emitter.onCompletion(() -> streams.remove(subscriber));
        return emitter;
    }

    @Override
    public void start()
    {
        running = true;
    }

    @Override
    public void stop()
    {
        running = false;
        streams.forEach(AgentSseSubscriber::close);
    }

    @Override
    public boolean isRunning()
    {
        return running;
    }

    @Override
    public int getPhase()
    {
        // A higher phase stops earlier, so the streams end before graceful shutdown starts waiting for them.
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;
    }
}
