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
package com.bytequay.app.developmentflow.execution;

import org.springframework.scheduling.annotation.Scheduled;

import static java.util.Objects.requireNonNull;

/** Uses Spring's existing scheduler only to renew and reap durable leases. */
public final class LegacyCapacityLeaseMaintainer
{
    private final LegacyCapacityBridge bridge;

    public LegacyCapacityLeaseMaintainer(LegacyCapacityBridge bridge)
    {
        this.bridge = requireNonNull(bridge, "bridge is null");
    }

    @Scheduled(
            initialDelayString = "${bytequay.development-flow.legacy-capacity-initial-delay-ms:1000}",
            fixedDelayString = "${bytequay.development-flow.legacy-capacity-heartbeat-ms:10000}")
    public void maintain()
    {
        bridge.maintainLeases();
    }
}
