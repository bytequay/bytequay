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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.ApprovalRequest;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** What a task last said and what it waits on, as a list of tasks summarizes it. */
public record TaskActivity(Optional<TaskMessage> lastMessage, Optional<ApprovalRequest> pendingApproval, Optional<AgentTurn> latestTurn)
{
    public TaskActivity
    {
        requireNonNull(lastMessage, "lastMessage is null");
        requireNonNull(pendingApproval, "pendingApproval is null");
        requireNonNull(latestTurn, "latestTurn is null");
    }
}
