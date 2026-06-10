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

import java.nio.file.Path;

/**
 * Per-invocation context handed to an {@link AgentTool}. Carries the
 * resolved working dir (so tools can sandbox path access to it), the
 * owning thread id (for tracing), the active task id when one is
 * focused, and an optional permission mediator the bridged-CLI
 * catalog uses to enforce gating. Future fields land here without
 * changing the tool ABI — adding a field to a record updates all
 * impls in one place.
 *
 * @param threadId           the owning thread's id.
 * @param taskId             the focused task id, or {@code null} for
 *                           trunk turns.
 * @param workingDir         absolute path the tool should resolve
 *                           relative paths against, and refuse to
 *                           escape from.
 * @param permissionMediator session-scoped policy hook used by the
 *                           CLI-bridge to gate mutating tools. Null
 *                           on the legacy ctor — bridged tools then
 *                           treat any non-{@code AUTO} gating as
 *                           denied, which is the conservative default.
 */
public record AgentToolContext(
        String threadId,
        String taskId,
        Path workingDir,
        ToolPermissionMediator permissionMediator)
{
    /** Legacy 3-arg constructor for call sites that don't care about
     *  the permission mediator (tests, native read-only tools). Null
     *  here forces bridged GATED / PARKED tools to refuse — the
     *  agent is expected to pass the 4-arg form in production. */
    public AgentToolContext(String threadId, String taskId, Path workingDir)
    {
        this(threadId, taskId, workingDir, /* permissionMediator */ null);
    }
}
