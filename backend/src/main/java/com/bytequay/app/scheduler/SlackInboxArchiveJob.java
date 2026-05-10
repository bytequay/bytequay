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
package com.bytequay.app.scheduler;

import com.bytequay.app.service.slack.SlackInboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Auto-archive sweeper for the inbox. Per the 2026-05-10 product
 * decision, RESPONDED items archive 4h after the user replied; this
 * job is what flips {@code archived_at = now()} once that window
 * elapses. The check is dirt-cheap (one indexed query) so a 60-second
 * cadence is plenty.
 */
@Component
public class SlackInboxArchiveJob
{
    private static final Logger log = LoggerFactory.getLogger(SlackInboxArchiveJob.class);

    private final SlackInboxService inboxService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SlackInboxArchiveJob(SlackInboxService inboxService)
    {
        this.inboxService = requireNonNull(inboxService, "inboxService is null");
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void tick()
    {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int archived = inboxService.autoArchiveExpired();
            if (archived > 0) {
                log.info("Auto-archived {} responded inbox item(s) past the 4h threshold", archived);
            }
        }
        catch (Exception e) {
            log.warn("Inbox auto-archive sweep failed: {}", e.getMessage());
        }
        finally {
            running.set(false);
        }
    }
}
