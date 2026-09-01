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
package com.bytequay.agent.cli.process.impl;

import com.bytequay.agent.cli.process.LaunchIntentRecorder;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records launches in memory, keeping the transition order for assertions. */
final class InMemoryLaunchIntentRecorder
        implements LaunchIntentRecorder
{
    private final Map<String, Boolean> committed = new ConcurrentHashMap<>();
    private final List<String> transitions = new CopyOnWriteArrayList<>();

    @Override
    public void prepared(String launchId, ProcessOwner owner, String executable, String argvDigest)
    {
        committed.put(launchId, false);
        transitions.add("prepared");
    }

    @Override
    public void attempting(String launchId)
    {
        committed.put(launchId, true);
        transitions.add("attempting");
    }

    @Override
    public Map<String, Boolean> committedLaunches()
    {
        return ImmutableMap.copyOf(committed);
    }

    @Override
    public void forget(String launchId)
    {
        committed.remove(launchId);
        transitions.add("forget");
    }

    /** Seeds a launch directly, standing in for a record committed by an earlier backend. */
    void commit(String launchId, boolean attempted)
    {
        committed.put(launchId, attempted);
    }

    Optional<Boolean> attempted(String launchId)
    {
        return Optional.ofNullable(committed.get(launchId));
    }

    List<String> transitions()
    {
        return ImmutableList.copyOf(transitions);
    }
}
