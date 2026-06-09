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
 * owning thread id (for tracing), and the active task id when one is
 * focused. Future fields land here without changing the tool ABI —
 * adding a field to a record updates all impls in one place.
 *
 * @param threadId    the owning thread's id.
 * @param taskId      the focused task id, or {@code null} for trunk
 *                    turns.
 * @param workingDir  absolute path the tool should resolve relative
 *                    paths against, and refuse to escape from.
 */
public record AgentToolContext(
        String threadId,
        String taskId,
        Path workingDir)
{
}
