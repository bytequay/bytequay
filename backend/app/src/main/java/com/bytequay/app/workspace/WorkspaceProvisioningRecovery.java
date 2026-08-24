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
package com.bytequay.app.workspace;

import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static java.util.Objects.requireNonNull;

/** Restarts interrupted managed clone and fetch operations after application startup. */
@Component
public final class WorkspaceProvisioningRecovery
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioningRecovery.class);
    private static final Duration CODE_FRESHNESS = Duration.ofMinutes(5);
    private static final Duration METADATA_FRESHNESS = Duration.ofDays(7);

    private final WorkspaceStore store;
    private final WorkspaceProvisioningService provisioning;
    private final WorkspaceMetadataService metadata;
    private final Clock clock;
    private final Executor executor;

    public WorkspaceProvisioningRecovery(
            WorkspaceStore store,
            WorkspaceProvisioningService provisioning,
            WorkspaceMetadataService metadata,
            Clock clock,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.store = requireNonNull(store, "store is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover()
    {
        store.findAll().forEach(this::schedule);
    }

    private void schedule(Workspace workspace)
    {
        try {
            Instant now = clock.instant();
            if (stale(workspace.lastCodeSyncAt(), now, CODE_FRESHNESS)) {
                executor.execute(workspace.lastCodeSyncAt().isPresent()
                        ? () -> provisioning.synchronizeCode(workspace.id())
                        : () -> provisioning.provision(workspace.id()));
            }
            if (stale(workspace.lastMetadataSyncAt(), now, METADATA_FRESHNESS)) {
                executor.execute(() -> metadata.synchronize(workspace.id()));
            }
        }
        catch (RuntimeException failure) {
            log.warn("Workspace {} recovery could not be scheduled: {}",
                    workspace.id(), failure.getMessage());
        }
    }

    private static boolean stale(Optional<Instant> syncedAt, Instant now, Duration freshness)
    {
        return syncedAt.filter(value -> !value.isBefore(now.minus(freshness))).isEmpty();
    }
}
