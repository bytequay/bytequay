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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.TaskId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * One subscription per owner, such as a review or a spec check, to the new events of the task it drives, so the owner hears
 * them with no renderer present. A second watch of an owner keeps the first subscription.
 */
public final class TaskEventWatcher
{
    private static final Logger log = LoggerFactory.getLogger(TaskEventWatcher.class);

    private final AgentEventService events;
    private final String owners;
    private final Map<String, Flow.Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * @param owners what the owners are, for the log, such as "Review"
     */
    public TaskEventWatcher(AgentEventService events, String owners)
    {
        this.events = requireNonNull(events, "events is null");
        this.owners = requireNonNull(owners, "owners is null");
    }

    /**
     * Hears the task's events from its log's current end; a task whose log cannot be read is not watched.
     *
     * @param onEvent sees each new event; it must hand work off, because it runs on the task's event stream
     * @param onEnd runs when the stream ends normally
     */
    public void watch(String owner, TaskId taskId, Consumer<AgentEventEnvelope> onEvent, Runnable onEnd)
    {
        if (subscriptions.containsKey(owner)) {
            return;
        }
        try {
            long after = events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()).latestSequence();
            events.subscribe(taskId, after).subscribe(new Flow.Subscriber<>()
            {
                @Override
                public void onSubscribe(Flow.Subscription subscription)
                {
                    if (subscriptions.putIfAbsent(owner, subscription) != null) {
                        subscription.cancel();
                        return;
                    }
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(AgentEventEnvelope item)
                {
                    // An exception here would cancel the subscription without onError.
                    try {
                        onEvent.accept(item);
                    }
                    catch (RuntimeException failure) {
                        log.warn("{} {} could not handle event {}: {}", owners, owner, item.eventId(), failure.toString());
                    }
                }

                @Override
                public void onError(Throwable failure)
                {
                    subscriptions.remove(owner);
                    log.warn("{} {} lost its task stream: {}", owners, owner, failure.toString());
                }

                @Override
                public void onComplete()
                {
                    subscriptions.remove(owner);
                    onEnd.run();
                }
            });
        }
        catch (RuntimeException failure) {
            log.warn("{} {} cannot watch its task {}: {}", owners, owner, taskId, failure.toString());
        }
    }

    public void unwatch(String owner)
    {
        Flow.Subscription subscription = subscriptions.remove(owner);
        if (subscription != null) {
            subscription.cancel();
        }
    }

    public void close()
    {
        subscriptions.values().forEach(Flow.Subscription::cancel);
        subscriptions.clear();
    }
}
