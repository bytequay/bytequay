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
package com.bytequay.app.service.tools;

import java.util.Set;

/**
 * Decides what a caller may do at tool-call time. The
 * {@link AgentToolRegistry} doesn't gate calls itself — it just
 * publishes the catalog; the resolver is the seam where authorisation
 * happens.
 *
 * <p>Today the implementation is a role→permission map keyed off the
 * thread's active task. The real permissions cascade (global →
 * workspace → thread → task, plus the initiator / autonomy envelope)
 * swaps in here behind the same interface — no caller changes.
 */
public interface PermissionResolver
{
    /** Resolve the role of the caller on {@code threadId}. The MCP
     *  endpoint is per-thread, so this drives both the {@code
     *  tools/list} filter and the security-type check at tools/call. */
    AgentRole roleFor(String threadId);

    /** Set of capability axes the caller on {@code threadId} is
     *  allowed to exercise. The registry refuses any tool whose
     *  {@link ToolSpec#security()} isn't in this set. */
    Set<SecurityType> grants(String threadId);
}
