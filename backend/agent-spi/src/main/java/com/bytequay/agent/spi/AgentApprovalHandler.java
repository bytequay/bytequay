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
package com.bytequay.agent.spi;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AgentApprovalHandler
{
    /**
     * Presents one provider permission request to ByteQuay for an explicit decision.
     *
     * <p>The returned stage completes with a user decision or a timeout denial. A
     * provider must not infer approval from a disconnect or incomplete stage.
     *
     * @param request provider-neutral permission request
     * @param timeout maximum time to await a decision
     * @return asynchronous allow or deny decision
     */
    CompletionStage<ProviderApprovalDecision> request(ProviderApprovalRequest request, Duration timeout);
}
