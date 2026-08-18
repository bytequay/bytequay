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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Ascii.truncate;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Component
final class CiAutofixMonitorJob
{
    private static final Logger log = LoggerFactory.getLogger(CiAutofixMonitorJob.class);
    private static final int MAXIMUM_LOG_MESSAGE_CHARACTERS = 1000;

    private final CiAutofixStore store;
    private final AgentTaskStore taskStore;
    private final CiAutofixService service;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    CiAutofixMonitorJob(
            CiAutofixStore store,
            AgentTaskStore taskStore,
            CiAutofixService service,
            @Value("${bytequay.ci-autofix.monitor-batch-size:20}") int batchSize)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.service = requireNonNull(service, "service is null");
        checkArgument(batchSize > 0, "batchSize must be positive");
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${bytequay.ci-autofix.monitor-initial-delay:PT30S}",
            fixedDelayString = "${bytequay.ci-autofix.monitor-interval:PT30S}")
    public void monitor()
    {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (CiAutofixRun run : store.listNonFinished(batchSize)) {
                advance(run);
            }
            int reconciled = 0;
            for (AgentTask task : taskStore.listLive()) {
                if (reconciled >= batchSize) {
                    break;
                }
                if (task.permissionPolicy() != AgentPermissionPolicy.CI_AUTOFIX) {
                    continue;
                }
                Optional<CiAutofixRun> run = store.findByAgentTaskId(task.id().toString())
                        .filter(value -> value.status() == CiAutofixRun.Status.FINISHED);
                if (run.isPresent()) {
                    advance(run.orElseThrow());
                    reconciled++;
                }
            }
        }
        finally {
            running.set(false);
        }
    }

    private void advance(CiAutofixRun run)
    {
        try {
            service.advance(run.id());
        }
        catch (RuntimeException failure) {
            log.warn(
                    "Cannot advance CI Autofix run {}: {}",
                    run.id(),
                    truncate(failureMessage(failure), MAXIMUM_LOG_MESSAGE_CHARACTERS, "…"));
        }
    }

    private static String failureMessage(RuntimeException failure)
    {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
