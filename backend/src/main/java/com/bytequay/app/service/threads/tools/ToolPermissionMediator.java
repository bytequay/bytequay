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
package com.bytequay.app.service.threads.tools;

import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.service.tools.Gating;

/**
 * Per-session policy hook that decides whether a tool call should
 * proceed. Lives outside the {@link AgentTool} contract because the
 * decision is session-scoped (per-tool budgets, permission prompts,
 * parked-proposal routing) and a static tool implementation has no
 * way to drive a user prompt on its own.
 *
 * <p>Wired through the API-lane {@link AgentToolContext} so a
 * {@link com.bytequay.app.service.threads.LogicLoopThreadAgent} can
 * provide its own implementation. Native tools that don't care
 * about gating (e.g. {@link ReadFileTool}) ignore it. The bridge to
 * the CLI-lane catalog
 * ({@link com.bytequay.app.service.tools.AgentToolRegistry}) calls
 * {@link #admit} before forwarding so a {@code Gating.GATED} tool
 * cannot run without the user's explicit ALLOW.
 */
public interface ToolPermissionMediator
{
    /**
     * Admit a call. Implementations should:
     *
     * <ul>
     *   <li>Return {@link PermissionDecision#ALLOW} for
     *       {@link Gating#AUTO} immediately.</li>
     *   <li>For {@link Gating#GATED}: try a per-tool budget first;
     *       on a hit, return ALLOW. Otherwise surface a
     *       {@link com.bytequay.app.domain.StreamEvent.PermissionRequested}
     *       event so the UI shows an Allow / Deny banner, block on
     *       {@link com.bytequay.app.service.threads.McpPermissionGate}
     *       until the user decides, emit the matching
     *       {@code PermissionDecided}, then return the user's
     *       decision.</li>
     *   <li>For {@link Gating#PARKED}: return
     *       {@link PermissionDecision#DENY} (the CLI lane's parked-
     *       proposal flow isn't wired into the API lane yet); the
     *       bridge surfaces a clear error explaining why.</li>
     * </ul>
     *
     * <p>The {@code summary} string is the model's own one-liner
     * describing what it wants to do — rendered verbatim in the
     * Allow / Deny banner.
     */
    PermissionDecision admit(String callId, String toolName, Gating gating, String summary);
}
