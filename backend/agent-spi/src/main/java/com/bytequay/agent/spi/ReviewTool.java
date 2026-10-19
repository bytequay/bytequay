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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One fixed review operation a provider session exposes to its root agent through the task
 * MCP endpoint.
 *
 * <p>{@code call} receives the tool arguments as one JSON object text and returns tool text. It
 * never throws for an invalid call: field-specific validation errors are tool text the model can
 * repair from, so a provider adapter never sees a review failure.
 */
public record ReviewTool(String name, String description, Map<String, Object> inputSchema, Function<String, String> call, boolean childCallable)
{
    public static final String NAME_PREFIX = "review_";
    /** The reads a read-only one-shot session may carry: a review pass's captured context, or the user's day for an agenda. */
    private static final Set<String> SESSION_CONTEXT_TOOLS = ImmutableSet.of("review_get_context", "agenda_context");
    /** The one save a read-only session may carry, kept from its children: the artifact writer's page. */
    private static final Set<String> SESSION_SAVE_TOOLS = ImmutableSet.of("artifact_save");
    /** The tools a tools-only session may carry: the assistant's read, its action, and what it shows the user. */
    private static final Set<String> ASSISTANT_TOOLS = ImmutableSet.of("assistant_context", "assistant_act", "assistant_show");
    private static final Set<String> CONTEXT_TOOLS = ImmutableSet.of(
            "repository_context_read",
            "repository_context_spec_search",
            "repository_context_spec_candidates",
            "repository_context_spec_result",
            "repository_context_harvest_threads",
            "repository_context_harvest_save",
            "goal_knowledge_save",
            "artifact_save",
            "agenda_context",
            "assistant_context",
            "assistant_act",
            "assistant_show");

    /** A tool only the root agent may call, which is every tool that saves or changes review state. */
    public ReviewTool(String name, String description, Map<String, Object> inputSchema, Function<String, String> call)
    {
        this(name, description, inputSchema, call, false);
    }

    /**
     * @param childCallable whether an exploration child the root agent starts may also call this tool;
     *         true only for a tool that reads
     */
    public ReviewTool
    {
        checkText(name, "name");
        checkText(description, "description");
        inputSchema = ImmutableMap.copyOf(inputSchema);
        requireNonNull(call, "call is null");
        if (!name.startsWith(NAME_PREFIX) && !CONTEXT_TOOLS.contains(name)) {
            throw new IllegalArgumentException("Unknown review capability tool name: " + name);
        }
    }

    /**
     * Checks the tools a one-shot session carries. A read-only session may start exploration children, so it carries context reads a
     * child may also call, and at most the artifact save, which only its root agent may call; a tools-only session has no children.
     */
    public static void checkSessionTools(SessionAccess access, List<ReviewTool> tools)
    {
        checkArgument(tools.isEmpty() || access == SessionAccess.READ_ONLY || access == SessionAccess.TOOLS_ONLY, "Session tools require a read-only or tools-only session");
        for (ReviewTool tool : tools) {
            if (access == SessionAccess.TOOLS_ONLY) {
                checkArgument(ASSISTANT_TOOLS.contains(tool.name()), "Only assistant tools are allowed in a tools-only session");
            }
            else {
                checkArgument(SESSION_CONTEXT_TOOLS.contains(tool.name()) && tool.childCallable()
                        || SESSION_SAVE_TOOLS.contains(tool.name()) && !tool.childCallable(), "Only context reads and a root-only artifact save are allowed");
            }
        }
    }
}
