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
package com.bytequay.agent.core.store;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.TurnId;

import java.util.List;
import java.util.Optional;

public interface AgentApprovalStore
{
    void insert(AgentApproval approval);

    Optional<AgentApproval> find(ApprovalId approvalId);

    /** Resolves only a still-pending row and returns whether this call won. */
    boolean resolve(
            ApprovalId approvalId,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome);

    List<AgentApproval> listPending(TurnId turnId);

    List<AgentApproval> listPending();
}
