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

import com.bytequay.app.domain.ThreadSettings;
import com.bytequay.app.repository.ThreadSettingsStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * <p>For Phase 6 the workspace layer is a pass-through (Phase 9+ adds
 * a workspace-level settings row). The global defaults come from
 * Spring properties so an operator can lower the workspace ceiling
 * without touching code.
 */
@Service
public class ThreadSettingsService
{
    private final ThreadSettingsStore store;
    private final int globalMaxRunningTasks;
    private final int globalSoftCostUsdMilli;
    private final int globalHardCostUsdMilli;

    public ThreadSettingsService(
            ThreadSettingsStore store,
            @Value("${bytequay.threads.settings.global-max-running-tasks:4}")
            int globalMaxRunningTasks,
            @Value("${bytequay.threads.settings.global-soft-cost-usd-milli:5000}")
            int globalSoftCostUsdMilli,
            @Value("${bytequay.threads.settings.global-hard-cost-usd-milli:20000}")
            int globalHardCostUsdMilli)
    {
        this.store = requireNonNull(store, "store is null");
        this.globalMaxRunningTasks = globalMaxRunningTasks;
        this.globalSoftCostUsdMilli = globalSoftCostUsdMilli;
        this.globalHardCostUsdMilli = globalHardCostUsdMilli;
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
        ThreadSettings withId = new ThreadSettings(
                threadId,
                settings.maxRunningTasks(),
                settings.softCostUsdMilli(),
                settings.hardCostUsdMilli(),
                settings.promptAddendum(),
                Instant.now());
        store.save(withId);
        return withId;
    }

    public void clear(String threadId)
    {
        store.clear(threadId);
    }

    /** Effective config the spawner / scheduler should consult.
     *  Resolves global → workspace → thread. */
    public EffectiveSettings effective(String threadId)
    {
        Optional<ThreadSettings> overrides = store.find(threadId);
        int maxRunningTasks = overrides.flatMap(o -> Optional.ofNullable(o.maxRunningTasks()))
                .orElse(globalMaxRunningTasks);
        int softCost = overrides.flatMap(o -> Optional.ofNullable(o.softCostUsdMilli()))
                .orElse(globalSoftCostUsdMilli);
        int hardCost = overrides.flatMap(o -> Optional.ofNullable(o.hardCostUsdMilli()))
                .orElse(globalHardCostUsdMilli);
        String promptAddendum = overrides.map(ThreadSettings::promptAddendum).orElse(null);
        return new EffectiveSettings(maxRunningTasks, softCost, hardCost, promptAddendum);
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
