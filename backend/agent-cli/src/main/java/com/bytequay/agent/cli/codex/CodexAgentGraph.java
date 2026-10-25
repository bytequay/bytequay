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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** The session serializes graph changes; its quiescence worker reads the concurrent inventories. */
final class CodexAgentGraph
        implements CodexEventMapper.ThreadLineage
{
    private final String rootThreadId;
    private final CodexLaunchProfile.ChildProfile childProfile;
    private final SecretRedactor sessionRedactor;
    private final Set<String> observedThreads = ConcurrentHashMap.newKeySet();
    private final Map<String, ChildFacts> children = new ConcurrentHashMap<>();
    private final Map<String, String> v2AgentPaths = new ConcurrentHashMap<>();
    private final Set<String> announcedChildren = ConcurrentHashMap.newKeySet();
    private final Set<String> promptedChildren = ConcurrentHashMap.newKeySet();

    CodexAgentGraph(String rootThreadId, CodexLaunchProfile.ChildProfile childProfile, SecretRedactor sessionRedactor)
    {
        this.rootThreadId = requireNonNull(rootThreadId, "rootThreadId is null");
        this.childProfile = requireNonNull(childProfile, "childProfile is null");
        this.sessionRedactor = requireNonNull(sessionRedactor, "sessionRedactor is null");
        observedThreads.add(rootThreadId);
        if (childProfile == CodexLaunchProfile.ChildProfile.V2) {
            v2AgentPaths.put(rootThreadId, "/root");
        }
    }

    @Override
    public Optional<String> parentOf(String threadId)
    {
        return Optional.ofNullable(children.get(threadId)).map(ChildFacts::parent);
    }

    @Override
    public Optional<String> labelOf(String threadId)
    {
        if (childProfile == CodexLaunchProfile.ChildProfile.V2) {
            // Provider identity uses /root; the shared event contract uses / for the root.
            if (threadId.equals(rootThreadId)) {
                return Optional.of("/");
            }
            return Optional.ofNullable(v2AgentPaths.get(threadId));
        }
        return Optional.ofNullable(children.get(threadId)).map(ChildFacts::label);
    }

    Set<String> observedThreads()
    {
        return ImmutableSet.copyOf(observedThreads);
    }

    void observeThread(String threadId)
    {
        observedThreads.add(threadId);
    }

    boolean hasChildren()
    {
        return !children.isEmpty();
    }

    List<String> childrenOf(String parentThreadId)
    {
        return children.entrySet().stream()
                .filter(child -> child.getValue().parent().equals(parentThreadId))
                .map(Map.Entry::getKey)
                .collect(toImmutableList());
    }

    void restoreChild(String parent, CodexProtocol.HistoryThread child)
    {
        if (childProfile == CodexLaunchProfile.ChildProfile.V2) {
            child.agentPath().ifPresent(path -> rememberV2AgentPath(child.id(), validateV2AgentPath(child.id(), parent, path, true)));
        }
        register(child.id(), parent, child.agentNickname().or(child::agentRole));
        announcedChildren.add(child.id());
    }

    boolean readyThreadStarted(CodexProtocol.Notification notification)
    {
        if (!(notification instanceof CodexProtocol.ThreadStartedNotification started)) {
            return false;
        }
        return started.params().thread().parentThreadId()
                .filter(parent -> parent.equals(rootThreadId) || confirmedLineage(parent))
                .isPresent();
    }

    /** Records what this process has shown us, so quiescence can be answered from it. */
    void observe(CodexProtocol.Notification notification)
    {
        observedThreads.add(notification.threadId());
        if (notification instanceof CodexProtocol.ThreadStartedNotification started) {
            CodexProtocol.HistoryThread thread = started.params().thread();
            // A missing parent is not evidence that this is a direct child of the root. Keep
            // observing the thread, but do not publish it until an explicit edge arrives.
            thread.parentThreadId().ifPresent(parent -> register(thread.id(), parent, thread.agentNickname().or(thread::agentRole)));
            return;
        }
        if (notification instanceof CodexProtocol.ItemStartedNotification started) {
            registerSpawned(started.params().item(), started.threadId());
        }
        else if (notification instanceof CodexProtocol.ItemCompletedNotification completed) {
            registerSpawned(completed.params().item(), completed.threadId());
        }
    }

    /** Applies the one edge format selected for this model process. */
    private void registerSpawned(CodexProtocol.ThreadItem item, String namingThread)
    {
        if (item instanceof CodexProtocol.CollabAgentToolCallItem collab) {
            if (!collab.senderThreadId().equals(namingThread)) {
                throw new AgentUnavailableException("Codex collaboration sender does not match its owning thread");
            }
            if (!collab.tool().equals("spawnAgent")) {
                collab.receiverThreadIds().forEach(this::requireKnownGraphIdentity);
                collab.agentsStates().keySet().forEach(this::requireKnownGraphIdentity);
                return;
            }
            if (childProfile != CodexLaunchProfile.ChildProfile.V1) {
                collab.receiverThreadIds().forEach(receiver -> {
                    requireKnownGraphIdentity(receiver);
                    enrichKnown(receiver, Optional.empty(), collab.prompt());
                });
                collab.agentsStates().keySet().forEach(this::requireKnownGraphIdentity);
                return;
            }
            collab.agentsStates().keySet().stream()
                    .filter(threadId -> !collab.receiverThreadIds().contains(threadId))
                    .forEach(this::requireKnownGraphIdentity);
            // The spawn carries what the parent asked for; the child never repeats it as a
            // message of its own, so this is the only record of its purpose.
            collab.receiverThreadIds().forEach(receiver -> register(receiver, namingThread, Optional.empty(), collab.prompt()));
            return;
        }
        if (item instanceof CodexProtocol.SubAgentActivityItem activity) {
            boolean edge = childProfile == CodexLaunchProfile.ChildProfile.V2
                    && activity.kind() == CodexProtocol.SubAgentActivityKind.STARTED;
            String agentPath = validateV2AgentPath(activity.agentThreadId(), namingThread, activity.agentPath(), edge);
            Optional<String> label = Optional.of(agentPath);
            if (!edge) {
                requireKnownGraphIdentity(activity.agentThreadId());
                if (childProfile == CodexLaunchProfile.ChildProfile.V2
                        && !v2AgentPaths.containsKey(activity.agentThreadId())) {
                    throw new AgentUnavailableException("Codex supplementary V2 activity has no canonical STARTED path");
                }
                rememberV2AgentPath(activity.agentThreadId(), agentPath);
                enrichKnown(activity.agentThreadId(), label, Optional.empty());
                return;
            }
            register(activity.agentThreadId(), namingThread, label);
            rememberV2AgentPath(activity.agentThreadId(), agentPath);
        }
    }

    private void requireKnownGraphIdentity(String threadId)
    {
        if (!threadId.equals(rootThreadId) && !routableThread(threadId)) {
            throw new AgentUnavailableException("Codex supplementary child activity has no confirmed graph identity");
        }
    }

    /** V2 paths are absolute and stable; a STARTED edge is nested below its direct parent. */
    private String validateV2AgentPath(String threadId, String parentThreadId, String rawPath, boolean edge)
    {
        Path path;
        try {
            path = Path.of(rawPath);
        }
        catch (RuntimeException invalid) {
            throw new AgentUnavailableException("Codex V2 agent path is invalid", invalid);
        }
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw new AgentUnavailableException("Codex V2 agent path is not absolute and normalized");
        }
        String existing = v2AgentPaths.get(threadId);
        if (existing != null && !existing.equals(rawPath)) {
            throw new AgentUnavailableException("Codex V2 agent path changed for an existing agent");
        }
        if (edge) {
            String parentPath = v2AgentPaths.get(parentThreadId);
            if (parentPath == null) {
                throw new AgentUnavailableException("Codex V2 child path has no canonical parent path");
            }
            Path parent = Path.of(parentPath);
            if (path.equals(parent) || !path.startsWith(parent)) {
                throw new AgentUnavailableException("Codex V2 child path is not nested below its direct parent");
            }
        }
        return rawPath;
    }

    private void rememberV2AgentPath(String threadId, String agentPath)
    {
        String existing = v2AgentPaths.putIfAbsent(threadId, agentPath);
        if (existing != null && !existing.equals(agentPath)) {
            throw new AgentUnavailableException("Codex V2 agent path changed for an existing agent");
        }
    }

    /** The nonselected item form may add metadata, but it cannot create a graph edge. */
    private void enrichKnown(String threadId, Optional<String> label, Optional<String> purpose)
    {
        if (threadId.equals(rootThreadId)) {
            return;
        }
        if (!routableThread(threadId)) {
            throw new AgentUnavailableException("Codex supplementary child activity has no confirmed graph identity");
        }
        children.computeIfPresent(threadId, (thread, existing) -> new ChildFacts(
                existing.parent(),
                resolvedChildLabel(threadId, label, existing.label()),
                purpose.or(existing::purpose)));
    }

    private void register(String threadId, String parentThreadId, Optional<String> label)
    {
        register(threadId, parentThreadId, label, Optional.empty());
    }

    private void register(String threadId, String parentThreadId, Optional<String> label, Optional<String> purpose)
    {
        if (threadId.equals(rootThreadId)) {
            throw new AgentUnavailableException("Codex reported the root thread as its own child");
        }
        if (threadId.equals(parentThreadId)) {
            throw new AgentUnavailableException("Codex reported a subagent as its own parent");
        }
        if (!parentThreadId.equals(rootThreadId) && !observedThreads.contains(parentThreadId)) {
            throw new AgentUnavailableException("Codex reported a subagent whose parent is not in this process");
        }
        String ancestor = parentThreadId;
        while (!ancestor.equals(rootThreadId)) {
            if (ancestor.equals(threadId)) {
                throw new AgentUnavailableException("Codex reported a cycle in its subagent lineage");
            }
            ChildFacts facts = children.get(ancestor);
            if (facts == null) {
                break;
            }
            ancestor = facts.parent();
        }
        observedThreads.add(threadId);
        children.compute(threadId, (thread, existing) -> {
            if (existing == null) {
                return new ChildFacts(parentThreadId, resolvedChildLabel(threadId, label, "Agent " + (children.size() + 1)), purpose);
            }
            if (!existing.parent().equals(parentThreadId)) {
                throw new AgentUnavailableException("Codex reported contradictory parents for a subagent");
            }
            // A nickname or prompt that arrives after discovery fills it in; a parent never changes.
            return new ChildFacts(existing.parent(), resolvedChildLabel(threadId, label, existing.label()), purpose.or(existing::purpose));
        });
    }

    /** A canonical V2 path, once known, cannot be replaced by thread metadata. */
    private String resolvedChildLabel(String threadId, Optional<String> reported, String fallback)
    {
        if (childProfile == CodexLaunchProfile.ChildProfile.V2) {
            String canonical = v2AgentPaths.get(threadId);
            if (canonical != null) {
                return canonical;
            }
        }
        return reported.orElse(fallback);
    }

    /** Returns each child's discovery and prompt once, after its lineage is in place. */
    List<ProviderAgentEvent> discoveryEvents()
    {
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        for (Map.Entry<String, ChildFacts> child : children.entrySet()) {
            if (!routableThread(child.getKey())) {
                continue;
            }
            String label = labelOf(child.getKey()).orElse(child.getValue().label());
            if (announcedChildren.add(child.getKey())) {
                events.add(new ProviderAgentEvent.Lifecycle(
                        Optional.of(child.getKey() + ":agent-started"),
                        ProviderAgentEvent.LifecyclePhase.AGENT_STARTED,
                        label,
                        childSource(child.getKey())));
            }
            // The spawn that carries the prompt can arrive before or after the thread it
            // creates, so the purpose is published when it is known rather than at discovery.
            if (child.getValue().purpose().isPresent() && promptedChildren.add(child.getKey())) {
                events.add(new ProviderAgentEvent.Message(
                        Optional.of(child.getKey() + ":agent-prompt"),
                        ProviderAgentEvent.MessageKind.PROMPT,
                        ProviderAgentEvent.MessagePhase.FINAL,
                        sessionRedactor.redact(child.getValue().purpose().orElseThrow()),
                        childSource(child.getKey())));
            }
        }
        return events.build();
    }

    /** Whether every parent edge from this child reaches the task's root. */
    private boolean confirmedLineage(String threadId)
    {
        Set<String> visited = new HashSet<>();
        String current = threadId;
        while (!current.equals(rootThreadId)) {
            if (!visited.add(current)) {
                return false;
            }
            ChildFacts facts = children.get(current);
            if (facts == null) {
                return false;
            }
            current = facts.parent();
        }
        return true;
    }

    /** A V2 child is routable only after its authoritative STARTED path is known. */
    boolean routableThread(String threadId)
    {
        return confirmedLineage(threadId)
                && (childProfile != CodexLaunchProfile.ChildProfile.V2
                        || v2AgentPaths.containsKey(threadId));
    }

    private ProviderAgentEventSource childSource(String threadId)
    {
        return new ProviderAgentEventSource(Optional.of(threadId), parentOf(threadId), labelOf(threadId), Optional.empty(), Optional.empty());
    }

    /** A subagent's place in the task: the thread that spawned it and the name it is shown under. */
    private record ChildFacts(String parent, String label, Optional<String> purpose) {}
}
