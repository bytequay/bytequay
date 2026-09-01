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

import com.bytequay.agent.cli.process.LaunchIntentRecorder;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.LaunchOwnerKind;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/**
 * Persists provider launch ownership so a crash always leaves a record naming what may be running.
 *
 * <p>This is the composition-root seam between the process-side owner kind owned by
 * {@code agent-cli} and the storage-side owner kind owned by {@code agent-core}. Keeping the two
 * enums separate is what lets {@code agent-cli} stay free of persistence.
 */
@Component
public class StoredLaunchIntentRecorder
        implements LaunchIntentRecorder
{
    private final AgentProcessLaunchStore store;
    private final Clock clock;

    public StoredLaunchIntentRecorder(AgentProcessLaunchStore store, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public void prepared(String launchId, ProcessOwner owner, String executable, String argvDigest)
    {
        requireNonNull(owner, "owner is null");
        store.insert(new AgentProcessLaunch(
                launchId,
                ownerKind(owner.kind()),
                owner.id(),
                false,
                executable,
                argvDigest,
                clock.instant()));
    }

    @Override
    public void attempting(String launchId)
    {
        store.markAttempted(launchId, clock.instant());
    }

    @Override
    public Map<String, Boolean> committedLaunches()
    {
        return store.list().stream()
                .collect(toImmutableMap(AgentProcessLaunch::launchId, AgentProcessLaunch::attempted));
    }

    @Override
    public void forget(String launchId)
    {
        store.delete(launchId);
    }

    private static LaunchOwnerKind ownerKind(ProcessOwner.Kind kind)
    {
        return switch (kind) {
            case APP_SERVER -> LaunchOwnerKind.APP_SERVER;
            case TASK_EXEC_SERVER -> LaunchOwnerKind.TASK_EXEC_SERVER;
            case CLAUDE_TURN -> LaunchOwnerKind.CLAUDE_TURN;
            case CLI_PROBE -> LaunchOwnerKind.CLI_PROBE;
        };
    }
}
