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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;

/**
 * A thread / task / stage row carries <em>reasoning effort only</em> — the
 * engine belongs to the workspace. This normalises whatever a client PUTs
 * into that shape, so a stale client (or a hand-rolled request) can't move
 * a live session onto another agent behind the workspace's back. It also
 * replaces the old "agent is locked after the first message" 409: an agent
 * switch is no longer something a scope can express.
 */
public final class ScopeWorkModel
{
    private ScopeWorkModel() {}

    /**
     * The row to persist for a scope-level work-model PUT: the resolved
     * engine wearing the requested reasoning effort, or {@code null} when
     * the request carries no effort (which clears the override and lets
     * the parent scope's effort apply).
     */
    public static WorkModel effortOnly(WorkModel resolvedEngine, WorkModel requested)
    {
        String effort = requested == null ? null : requested.reasoningEffort();
        if (effort == null || effort.isBlank()) {
            return null;
        }
        return new WorkModel(
                resolvedEngine.kind(),
                resolvedEngine.agentOrProvider(),
                resolvedEngine.model(),
                resolvedEngine.account(),
                effort);
    }
}
