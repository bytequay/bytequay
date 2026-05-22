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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Cleans up threads the previous backend process left running. Any
 * {@link ThreadStatus#RUNNING} row at startup is by definition orphaned
 * — its subprocess is gone — so we flip it to {@link ThreadStatus#IDLE}.
 * The user can resume by sending a follow-up turn, which spawns a
 * fresh {@code claude --resume <session-id>}.
 *
 * <p>Other non-terminal statuses ({@code PENDING}, {@code AWAITING},
 * {@code IDLE}) are already correct; sessions get re-created lazily
 * by {@link ThreadRegistry#getOrCreate} on first hit.
 */
@Component
public class ThreadStartupReconciler
{
    private static final Logger log = LoggerFactory.getLogger(ThreadStartupReconciler.class);

    private static final int RECONCILE_PAGE_SIZE = 1_000;

    private final ThreadStore store;

    public ThreadStartupReconciler(ThreadStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup()
    {
        int reconciled = 0;
        while (true) {
            List<Thread> orphaned = store.listTasksByStatus(ThreadStatus.RUNNING, RECONCILE_PAGE_SIZE);
            if (orphaned.isEmpty()) {
                break;
            }
            Instant now = Instant.now();
            for (Thread thread : orphaned) {
                store.saveThread(new Thread(
                        thread.id(), thread.kind(), thread.provider(), thread.agentSessionId(),
                        thread.title(), ThreadStatus.IDLE,
                        thread.model(),
                        thread.costUsdMilli(), thread.tokensIn(), thread.tokensOut(),
                        thread.createdAt(), now,
                        thread.endedAt(), thread.errorMessage(),
                        thread.flow(),
                        thread.activeTask()));
            }
            reconciled += orphaned.size();
            if (orphaned.size() < RECONCILE_PAGE_SIZE) {
                break;
            }
        }
        if (reconciled > 0) {
            log.info("Reconciled {} orphaned RUNNING thread(s) to IDLE", reconciled);
        }
    }
}
