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

import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.service.tools.PermissionResolver;

import java.nio.file.Path;

/**
 * Per-invocation context handed to an {@link AgentTool}. Carries the
 * resolved working dir (so tools can sandbox path access to it), the
 * owning thread id (for tracing), the active task/stage scope, the
 * thread kind, and an optional permission mediator the bridged-CLI
 * catalog uses to enforce gating. Future fields land here without
 * changing the tool ABI — adding a field to a record updates all impls
 * in one place.
 *
 * @param threadId           the owning thread's id.
 * @param scope              authoritative scope of the running turn.
 * @param taskId             the focused task id, or {@code null} for
 *                           trunk turns.
 * @param workingDir         absolute path the tool should resolve
 *                           relative paths against, and refuse to
 *                           escape from.
 * @param permissionMediator session-scoped policy hook used by the
 *                           CLI-bridge to gate mutating tools. Null means
 *                           bridged non-{@code AUTO} tools are denied.
 * @param stageId            the focused stage id, or {@code null} for
 *                           task-level / trunk turns.
 * @param agentRunId         the focused AgentRun episode id, or
 *                           {@code null} for ordinary turns.
 * @param threadKind         the thread kind executing the tool call;
 *                           used by bridged tools to enforce kind gates.
 */
public record AgentToolContext(
        String threadId,
        ThreadScope scope,
        String taskId,
        Path workingDir,
        ToolPermissionMediator permissionMediator,
        String stageId,
        String agentRunId,
        ThreadKind threadKind)
{
    public AgentToolContext
    {
        PermissionResolver.agentKeyFor(scope, taskId);
        if (scope == ThreadScope.STAGE && (stageId == null || stageId.isBlank())) {
            throw new IllegalArgumentException("STAGE tool context requires stageId");
        }
        if (scope != ThreadScope.STAGE && stageId != null && !stageId.isBlank()) {
            throw new IllegalArgumentException(scope + " tool context forbids stageId");
        }
    }
}
