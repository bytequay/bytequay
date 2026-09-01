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

import com.bytequay.agent.core.AgentProcessLaunch;

import java.time.Instant;
import java.util.List;

/** Durable ownership records for supervised provider processes. */
public interface AgentProcessLaunchStore
{
    /** Commits a new launch before its payload can be spawned. */
    void insert(AgentProcessLaunch launch);

    /** Records that the owner committed to spawning the payload. */
    void markAttempted(String launchId, Instant at);

    /** Returns every committed launch, ordered by identifier so recovery is deterministic. */
    List<AgentProcessLaunch> list();

    /** Deletes an acknowledged launch; absent rows are ignored. */
    void delete(String launchId);
}
