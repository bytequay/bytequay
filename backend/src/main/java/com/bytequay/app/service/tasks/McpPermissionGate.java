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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.PermissionDecision;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * Coordination point between the MCP {@code approval_prompt} tool
 * call (which has to block the agent until the user decides) and
 * {@link AgentSession#decide}, which the user invokes through the
 * Allow / Deny banner in the conversation pane.
 *
 * <p>Lifecycle for one prompt:
 * <ol>
 *   <li>Claude calls {@code tools/call approval_prompt} with a
 *       {@code tool_use_id}; the MCP controller registers a future
 *       under that id and asks Spring to defer the response.</li>
 *   <li>The user clicks Allow / Deny; the session's {@code decide}
 *       hook calls {@link #decide} which completes the future.</li>
 *   <li>The MCP controller serializes the decision into the response
 *       Claude expects and returns it.</li>
 * </ol>
 *
 * <p>Decisions arriving without a matching pending future are
 * dropped — they're either replays, or the user clicked before the
 * agent actually asked. Either way, no harm.
 */
@Component
public class McpPermissionGate
{
    private final ConcurrentHashMap<String, CompletableFuture<PermissionDecision>> pending =
            new ConcurrentHashMap<>();

    /** Register a fresh future for {@code callId}; the MCP controller
     *  awaits this to know what to send back to Claude. Calling
     *  {@code register} a second time for the same callId returns the
     *  existing future — Claude shouldn't ever do this, but if it
     *  retries, we don't want to leak an extra slot. */
    public CompletableFuture<PermissionDecision> register(String callId)
    {
        requireNonNull(callId, "callId is null");
        return pending.computeIfAbsent(callId, k -> new CompletableFuture<>());
    }

    /** Resolve a pending request. Idempotent — only the first call
     *  for a given {@code callId} wins; later calls are dropped. */
    public void decide(String callId, PermissionDecision decision)
    {
        requireNonNull(callId, "callId is null");
        requireNonNull(decision, "decision is null");
        CompletableFuture<PermissionDecision> future = pending.remove(callId);
        if (future != null) {
            future.complete(decision);
        }
    }

    /** Drop a pending request without resolving it — used when the
     *  session is stopped while a permission was outstanding so the
     *  controller's {@code DeferredResult} doesn't hang the worker
     *  thread forever. */
    public void cancel(String callId)
    {
        requireNonNull(callId, "callId is null");
        CompletableFuture<PermissionDecision> future = pending.remove(callId);
        if (future != null) {
            future.cancel(true);
        }
    }
}
