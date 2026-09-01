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

import com.bytequay.agent.cli.process.LaunchIntent;
import com.bytequay.agent.cli.process.LaunchIntentRecorder;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records launch intents in memory, keeping the transition order for assertions. */
final class InMemoryLaunchIntentRecorder
        implements LaunchIntentRecorder
{
    private final Map<String, LaunchIntent> intents = new ConcurrentHashMap<>();
    private final List<String> transitions = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> results = new ConcurrentHashMap<>();

    @Override
    public void prepared(String launchId, ProcessOwner owner, String executable, String argvDigest)
    {
        intents.put(launchId, new LaunchIntent(launchId, LaunchIntent.State.PREPARED));
        transitions.add("prepared");
    }

    @Override
    public void attempting(String launchId)
    {
        intents.put(launchId, new LaunchIntent(launchId, LaunchIntent.State.ATTEMPTING));
        transitions.add("attempting");
    }

    @Override
    public void running(String launchId)
    {
        intents.put(launchId, new LaunchIntent(launchId, LaunchIntent.State.RUNNING));
        transitions.add("running");
    }

    @Override
    public void terminal(String launchId, boolean verifiedStopped)
    {
        intents.put(launchId, new LaunchIntent(launchId, LaunchIntent.State.TERMINAL));
        results.put(launchId, verifiedStopped);
        transitions.add("terminal");
    }

    @Override
    public Optional<LaunchIntent> find(String launchId)
    {
        return Optional.ofNullable(intents.get(launchId));
    }

    @Override
    public List<LaunchIntent> list()
    {
        return ImmutableList.copyOf(intents.values());
    }

    @Override
    public void forget(String launchId)
    {
        intents.remove(launchId);
        transitions.add("forget");
    }

    /** Seeds an intent directly, standing in for a record committed by an earlier backend. */
    void commit(String launchId, LaunchIntent.State state)
    {
        intents.put(launchId, new LaunchIntent(launchId, state));
    }

    List<String> transitions()
    {
        return ImmutableList.copyOf(transitions);
    }

    Optional<Boolean> verifiedStopped(String launchId)
    {
        return Optional.ofNullable(results.get(launchId));
    }
}
