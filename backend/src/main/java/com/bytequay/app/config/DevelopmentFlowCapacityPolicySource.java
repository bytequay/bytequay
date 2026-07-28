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
package com.bytequay.app.config;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.domain.ThreadSettings;
import com.bytequay.app.repository.ThreadSettingsStore;
import com.bytequay.app.repository.ThreadStore;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Projects legacy Trunk concurrency settings into the shared capacity policy. */
final class DevelopmentFlowCapacityPolicySource
        implements CapacityManager.CapacityPolicySource
{
    private final ThreadStore threads;
    private final ThreadSettingsStore settings;
    private final int defaultWorkspaceLimit;
    private final int defaultTrunkLimit;

    DevelopmentFlowCapacityPolicySource(
            ThreadStore threads,
            ThreadSettingsStore settings,
            int defaultWorkspaceLimit,
            int defaultTrunkLimit)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.defaultWorkspaceLimit = positive(
                defaultWorkspaceLimit, "defaultWorkspaceLimit");
        this.defaultTrunkLimit = positive(defaultTrunkLimit, "defaultTrunkLimit");
    }

    @Override
    public CapacityManager.CapacityPolicy current()
    {
        return CapacityManager.CapacityPolicy.initial(
                defaultWorkspaceLimit, defaultTrunkLimit, Map.of());
    }

    @Override
    public CapacityManager.CapacityPolicy current(
            CapacityManager.CapacityRequest request)
    {
        requireNonNull(request, "request is null");
        String trunkId = request.scope().trunkId();
        if (trunkId == null) {
            return current();
        }
        int trunkLimit = settings.find(trunkId)
                .map(ThreadSettings::maxRunningTasks)
                .filter(value -> value != null)
                .orElseGet(() -> threads.findThreadById(trunkId)
                        // parallel_slots=1 was the legacy sequential default;
                        // values above one were explicit and remain policy.
                        .filter(thread -> thread.parallelSlots() > 1)
                        .map(thread -> thread.parallelSlots())
                        .orElse(defaultTrunkLimit));
        CapacityManager.CapacityPolicy base = current();
        return new CapacityManager.CapacityPolicy(
                base.laneLimits(),
                base.reservedTrunkControl(),
                defaultWorkspaceLimit,
                defaultTrunkLimit,
                Map.of(),
                Map.of(trunkId, trunkLimit));
    }

    private static int positive(int value, String name)
    {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
