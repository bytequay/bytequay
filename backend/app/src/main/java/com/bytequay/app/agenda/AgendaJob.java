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
package com.bytequay.app.agenda;

import com.bytequay.app.review.PullRequestsRefreshed;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static java.util.Objects.requireNonNull;

/** Looks each minute whether today's agenda is due; a Mac that slept past the set time gets it on the first look after waking. */
@Component
public class AgendaJob
{
    private final AgendaService agenda;
    private final Clock clock;
    private final ExecutorService executor;
    private final AtomicReference<Instant> lastSync = new AtomicReference<>();

    public AgendaJob(AgendaService agenda, Clock clock, @Qualifier(IO_EXECUTOR) ExecutorService executor)
    {
        this.agenda = requireNonNull(agenda, "agenda is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    @EventListener
    public void synced(PullRequestsRefreshed refreshed)
    {
        lastSync.set(clock.instant());
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void tick()
    {
        // Every @Scheduled job shares the agent approval thread, and a run waits minutes on the model.
        executor.execute(() -> agenda.runIfDue(Optional.ofNullable(lastSync.get())));
    }
}
