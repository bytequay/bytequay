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
package com.bytequay.app.service.runs;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.threads.ThreadRegistry;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

import static java.util.Objects.requireNonNull;

/** Fail-closed controls for historical AgentRun rows. */
@Service
public class SessionControlService
{
    private final AgentRunServiceImpl runs;

    public SessionControlService(
            AgentRunServiceImpl runs,
            ThreadStore threads,
            ThreadTurnStore turns,
            ThreadTurnScheduler scheduler,
            ThreadRegistry registry)
    {
        this.runs = requireNonNull(runs, "runs is null");
        requireNonNull(threads, "threads is null");
        requireNonNull(turns, "turns is null");
        requireNonNull(scheduler, "scheduler is null");
        requireNonNull(registry, "registry is null");
    }

    public AgentRun pause(String runId)
    {
        return reject(runId);
    }

    public AgentRun stop(String runId)
    {
        return reject(runId);
    }

    public AgentRun resume(String runId)
    {
        return reject(runId);
    }

    public AgentRun restart(String runId)
    {
        return reject(runId);
    }

    private AgentRun reject(String runId)
    {
        if (runs.findById(runId).isEmpty()) {
            throw new NoSuchElementException("no session: " + runId);
        }
        throw new UnsupportedOperationException(
                "LEGACY AgentRun control is retired; use the typed V2 owner control");
    }
}
