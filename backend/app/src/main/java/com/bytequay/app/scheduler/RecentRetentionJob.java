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

import com.bytequay.app.service.recent.RecentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Enforces the rolling retention window. A sweep rather than a read-time filter,
 * so a record of the user's reading habits does not sit on disk indefinitely
 * just because nobody opened the page.
 */
@Component
public class RecentRetentionJob
{
    private static final Logger log = LoggerFactory.getLogger(RecentRetentionJob.class);

    private final RecentService recent;

    public RecentRetentionJob(RecentService recent)
    {
        this.recent = requireNonNull(recent, "recent is null");
    }

    @Scheduled(
            initialDelayString = "${bytequay.recent.sweep-initial-delay:PT30S}",
            fixedDelayString = "${bytequay.recent.sweep-interval:PT6H}")
    public void tick()
    {
        try {
            int removed = recent.sweep();
            if (removed > 0) {
                log.info("Recent retention sweep dropped {} rows older than {}", removed, recent.windowStart());
            }
        }
        catch (RuntimeException failure) {
            log.warn("Recent retention sweep failed: {}", failure.getMessage());
        }
    }
}
