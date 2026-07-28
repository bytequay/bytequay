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
package com.bytequay.app.service.agents;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.service.tools.PermissionResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Shares the scheduler's resolved turn contract with the task-bound MCP endpoint. */
@Component
public class ActiveAgentContextRegistry
{
    private final ConcurrentHashMap<Key, Entry> active = new ConcurrentHashMap<>();

    public void put(String threadId, String agentKey, ResolvedAgentContext context)
    {
        put(threadId, agentKey, context, PermissionResolver.RunningScope.NONE);
    }

    public void put(
            String threadId,
            String agentKey,
            ResolvedAgentContext context,
            PermissionResolver.RunningScope scope)
    {
        put(threadId, agentKey, context, scope, null);
    }

    public void put(
            String threadId,
            String agentKey,
            ResolvedAgentContext context,
            PermissionResolver.RunningScope scope,
            TypedOwner typedOwner)
    {
        active.put(new Key(threadId, agentKey),
                new Entry(context, scope, typedOwner));
    }

    public Optional<ResolvedAgentContext> find(String threadId, String agentKey)
    {
        return Optional.ofNullable(active.get(new Key(threadId, agentKey)))
                .map(Entry::context);
    }

    public Optional<PermissionResolver.RunningScope> findScope(
            String threadId, String agentKey)
    {
        return Optional.ofNullable(active.get(new Key(threadId, agentKey)))
                .map(Entry::scope)
                .filter(scope -> scope.scope() != null);
    }

    public Optional<TypedOwner> findTypedOwner(String threadId, String agentKey)
    {
        return Optional.ofNullable(active.get(new Key(threadId, agentKey)))
                .map(Entry::typedOwner);
    }

    /** Attaches the live provider stop hook after its session is open. */
    public boolean attachStop(String threadId, String agentKey, Runnable stop)
    {
        if (stop == null) {
            throw new IllegalArgumentException("stop is null");
        }
        Entry entry = active.get(new Key(threadId, agentKey));
        return entry != null && entry.attachStop(stop);
    }

    /** Records a durable-wait reason before interrupting the exact provider. */
    public boolean requestStop(
            String threadId, String agentKey, String reason)
    {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is blank");
        }
        Entry entry = active.get(new Key(threadId, agentKey));
        return entry != null && entry.requestStop(reason);
    }

    public Optional<String> stopReason(String threadId, String agentKey)
    {
        Entry entry = active.get(new Key(threadId, agentKey));
        return entry == null ? Optional.empty() : entry.stopReason();
    }

    public void remove(String threadId, String agentKey)
    {
        active.remove(new Key(threadId, agentKey));
    }

    int size()
    {
        return active.size();
    }

    private record Key(String threadId, String agentKey) {}

    private static final class Entry
    {
        private final ResolvedAgentContext context;
        private final PermissionResolver.RunningScope scope;
        private final TypedOwner typedOwner;
        private final AtomicReference<Runnable> stop = new AtomicReference<>();
        private final AtomicReference<String> stopReason = new AtomicReference<>();

        private Entry(
                ResolvedAgentContext context,
                PermissionResolver.RunningScope scope,
                TypedOwner typedOwner)
        {
            this.context = context;
            this.scope = scope;
            this.typedOwner = typedOwner;
        }

        private ResolvedAgentContext context()
        {
            return context;
        }

        private PermissionResolver.RunningScope scope()
        {
            return scope;
        }

        private TypedOwner typedOwner()
        {
            return typedOwner;
        }

        private boolean attachStop(Runnable callback)
        {
            return stop.compareAndSet(null, callback);
        }

        private boolean requestStop(String reason)
        {
            Runnable callback = stop.get();
            if (callback == null || !stopReason.compareAndSet(null, reason)) {
                return false;
            }
            callback.run();
            return true;
        }

        private Optional<String> stopReason()
        {
            return Optional.ofNullable(stopReason.get());
        }
    }

    public record TypedOwner(
            DispatchTicket.OwnerKind kind,
            String turnId,
            String operationId)
    {
        public TypedOwner
        {
            if (kind == null || turnId == null || turnId.isBlank()
                    || operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("typed owner is incomplete");
            }
        }
    }
}
