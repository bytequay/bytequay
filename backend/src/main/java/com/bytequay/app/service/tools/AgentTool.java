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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an agent tool handler. The
 * {@link AgentToolRegistry} scans Spring beans for these on startup,
 * derives a {@link ToolSpec} per method (deriving the JSON
 * {@code inputSchema} from the handler's first parameter — a typed
 * args record with {@link ToolParam}-annotated components), and
 * exposes the resulting list to the MCP server + future in-JVM lane.
 *
 * <p>The annotation only declares <em>capability</em>. Whether a
 * caller may actually invoke the tool is decided by the resolver
 * (security type ∈ caller's set) and the gating (AUTO / GATED /
 * PARKED).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool
{
    /** Tool name as it appears on the wire. Conventionally
     *  snake_case to match MCP / Claude tool naming. Must be unique
     *  across the registry; registry startup fails on a collision. */
    String name();

    /** One-paragraph description the model sees in {@code tools/list}.
     *  Same field MCP exposes as {@code tool.description}. */
    String description();

    /** Coarse capability axis the tool exercises — see
     *  {@link SecurityType}. The resolver gates on this. */
    SecurityType security();

    /** Admission mode at dispatch time — see {@link Gating}. */
    Gating gating();

    /** Roles allowed to discover and call this tool. An empty array
     *  is treated as {@code {AgentRole.ANY}}. */
    AgentRole[] roles() default {AgentRole.ANY};

    /** Optional usage-pattern hint surfaced to the model alongside
     *  the description. Empty by default — most tools' description
     *  already covers when to use them. */
    String whenToUse() default "";
}
