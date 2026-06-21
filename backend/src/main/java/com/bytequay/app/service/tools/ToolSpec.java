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

import com.bytequay.app.domain.ThreadKind;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Registry's view of a single agent tool. Derived once at startup
 * from a {@link AgentTool}-annotated handler method; immutable
 * afterward.
 *
 * @param name           tool name on the wire
 * @param description    one-paragraph description for tools/list
 * @param whenToUse      optional usage hint; empty when the tool's
 *                       description already covers it
 * @param security       coarse capability axis the tool exercises
 * @param gating         AUTO / GATED / PARKED admission mode
 * @param roles          which agent roles may discover + call it
 * @param kinds          which thread kinds may discover + call it on the
 *                       MCP path; empty means no kind restriction
 * @param inputSchema    JSON Schema (Anthropic / MCP shape) string
 *                       generated from the handler's args record
 * @param argsType       the typed args record class — used by the
 *                       dispatcher to bind the incoming JSON
 * @param handlerBean    the Spring bean owning the handler method
 * @param handlerMethod  the reflective handle to invoke at dispatch
 */
public record ToolSpec(
        String name,
        String description,
        String whenToUse,
        SecurityType security,
        Gating gating,
        Set<AgentRole> roles,
        Set<ThreadKind> kinds,
        String inputSchema,
        Class<?> argsType,
        Object handlerBean,
        Method handlerMethod)
{
    /** True when this tool is visible to {@code role}. ANY in the
     *  spec's roles array means every role. */
    public boolean availableTo(AgentRole role)
    {
        if (roles.contains(AgentRole.ANY)) {
            return true;
        }
        return roles.contains(role);
    }

    /** True when this tool is callable by a thread of {@code kind}. An
     *  empty {@code kinds} set means no kind restriction (every kind). A
     *  null kind (caller kind couldn't be resolved) passes only when the
     *  tool declares no kind restriction. */
    public boolean availableToKind(ThreadKind kind)
    {
        if (kinds.isEmpty()) {
            return true;
        }
        return kind != null && kinds.contains(kind);
    }
}
