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

    public void remove(String threadId, String agentKey)
    {
        active.remove(new Key(threadId, agentKey));
    }

    int size()
    {
        return active.size();
    }

    private record Key(String threadId, String agentKey) {}

    private record Entry(
            ResolvedAgentContext context,
            PermissionResolver.RunningScope scope,
            TypedOwner typedOwner) {}

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
