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
package com.bytequay.app.backport;

import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.scheduler.MonitorLoop;
import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChainStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.google.common.collect.ImmutableList.toImmutableList;

@Component
final class BackportChainMonitorJob
{
    private final BackportChainStore store;
    private final BackportChainService service;
    private final AgentServingGate gate;
    private final MonitorLoop loop = new MonitorLoop(BackportChainMonitorJob.class, "backport chain");

    BackportChainMonitorJob(BackportChainStore store, BackportChainService service, AgentServingGate gate)
    {
        this.store = store;
        this.service = service;
        this.gate = gate;
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT5S")
    public void monitor()
    {
        if (gate.isReady()) {
            loop.advanceEach(store.list().stream()
                    .filter(chain -> chain.state() == BackportChain.State.ACTIVE || chain.state() == BackportChain.State.STOPPING)
                    .sorted(java.util.Comparator.comparing(BackportChain::updatedAt))
                    .limit(20).map(BackportChain::id).collect(toImmutableList()), service::advance);
        }
    }
}
