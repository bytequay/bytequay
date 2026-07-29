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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.domain.ThreadSettings;
import com.bytequay.app.repository.ThreadSettingsStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Resolves the effective scope config for a Thread.
 *
 * <p>The hierarchy is {@code global → workspace → thread → task};
 * task-scope is future work, so the merge today is global → workspace
 * → thread. Per the workspace/thread/task design's "Thread as a scope"
 * section, a thread row in {@code thread_settings} only exists when
 * the thread tightens or overrides one of the inherited values — a
 * fresh thread silently inherits ({@link EffectiveSettings#fromGlobal}).
 *
 * <p>Workspace capacity is enforced separately by CapacityManager. This
 * service resolves the Trunk override and the same configured Trunk default
 * used by admission, so its API never presents a second concurrency policy.
 */
@Service
public class ThreadSettingsService
{
    private final ThreadSettingsStore store;
    private final int defaultTrunkMaxRunningTasks;
    private final int globalSoftCostUsdMilli;
    private final int globalHardCostUsdMilli;
    private final CapacityManager capacity;

    public ThreadSettingsService(
            ThreadSettingsStore store,
            @Value("${bytequay.development-flow.capacity.default-trunk-running-tasks:4}")
            int defaultTrunkMaxRunningTasks,
            @Value("${bytequay.threads.settings.global-soft-cost-usd-milli:5000}")
            int globalSoftCostUsdMilli,
            @Value("${bytequay.threads.settings.global-hard-cost-usd-milli:20000}")
            int globalHardCostUsdMilli,
            CapacityManager capacity)
    {
        this.store = requireNonNull(store, "store is null");
        this.defaultTrunkMaxRunningTasks = defaultTrunkMaxRunningTasks;
        this.globalSoftCostUsdMilli = globalSoftCostUsdMilli;
        this.globalHardCostUsdMilli = globalHardCostUsdMilli;
        this.capacity = requireNonNull(capacity, "capacity is null");
    }

    /** Whatever the user has explicitly set on this thread; empty when
     *  the thread is in zero-config (inherit-everything) mode. */
    public Optional<ThreadSettings> findOverrides(String threadId)
    {
        return store.find(threadId);
    }

    /** Upsert the thread's overrides. {@code null} fields fall back to
     *  the workspace / global defaults. */
    public ThreadSettings save(String threadId, ThreadSettings settings)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(settings, "settings is null");
        if (settings.maxRunningTasks() != null
                && settings.maxRunningTasks() < 1) {
            throw new IllegalArgumentException(
                    "thread max running tasks must be positive");
        }
        ThreadSettings withId = new ThreadSettings(
                threadId,
                settings.maxRunningTasks(),
                settings.softCostUsdMilli(),
                settings.hardCostUsdMilli(),
                settings.promptAddendum(),
                Instant.now());
        store.save(withId);
        signalCapacityPolicyChange();
        return withId;
    }

    public void clear(String threadId)
    {
        store.clear(threadId);
        signalCapacityPolicyChange();
    }

    /** Effective config the spawner / scheduler should consult.
     *  Resolves global → workspace → thread. */
    public EffectiveSettings effective(String threadId)
    {
        Optional<ThreadSettings> overrides = store.find(threadId);
        int maxRunningTasks = overrides.flatMap(o -> Optional.ofNullable(o.maxRunningTasks()))
                .orElse(defaultTrunkMaxRunningTasks);
        int softCost = overrides.flatMap(o -> Optional.ofNullable(o.softCostUsdMilli()))
                .orElse(globalSoftCostUsdMilli);
        int hardCost = overrides.flatMap(o -> Optional.ofNullable(o.hardCostUsdMilli()))
                .orElse(globalHardCostUsdMilli);
        String promptAddendum = overrides.map(ThreadSettings::promptAddendum).orElse(null);
        return new EffectiveSettings(maxRunningTasks, softCost, hardCost, promptAddendum);
    }

    private void signalCapacityPolicyChange()
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            capacity.policyChanged();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        capacity.policyChanged();
                    }
                });
    }

    /** Resolved view that the agent spawner / scheduler reads at run
     *  time. Every field is non-null — the merge fills holes from the
     *  global defaults. */
    public record EffectiveSettings(
            int maxRunningTasks,
            int softCostUsdMilli,
            int hardCostUsdMilli,
            String promptAddendum)
    {
    }
}
