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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

final class AgentSseSubscriber
        implements Flow.Subscriber<AgentEventEnvelope>
{
    private final SseEmitter emitter;
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private final AtomicBoolean stopped = new AtomicBoolean();

    AgentSseSubscriber(SseEmitter emitter)
    {
        this.emitter = requireNonNull(emitter, "emitter is null");
        emitter.onCompletion(this::cancel);
        emitter.onTimeout(this::cancel);
        emitter.onError(ignored -> cancel());
    }

    @Override
    public void onSubscribe(Flow.Subscription newSubscription)
    {
        requireNonNull(newSubscription, "newSubscription is null");
        if (!subscription.compareAndSet(null, newSubscription) || stopped.get()) {
            newSubscription.cancel();
            return;
        }
        newSubscription.request(1);
    }

    @Override
    public void onNext(AgentEventEnvelope event)
    {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.type().name().toLowerCase(Locale.ROOT))
                    .data(AgentHttpModels.event(event)));
            Flow.Subscription current = subscription.get();
            if (current != null) {
                current.request(1);
            }
        }
        catch (IOException | RuntimeException failure) {
            if (stopped.compareAndSet(false, true)) {
                cancelSubscription();
                emitter.completeWithError(failure);
            }
        }
    }

    @Override
    public void onError(Throwable failure)
    {
        if (stopped.compareAndSet(false, true)) {
            emitter.completeWithError(failure);
        }
    }

    @Override
    public void onComplete()
    {
        if (stopped.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private void cancel()
    {
        if (stopped.compareAndSet(false, true)) {
            cancelSubscription();
        }
    }

    private void cancelSubscription()
    {
        Flow.Subscription current = subscription.getAndSet(null);
        if (current != null) {
            current.cancel();
        }
    }
}
