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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Single tool the API-lane {@link
 * com.bytequay.app.service.threads.LogicLoopThreadAgent} can call.
 * The interface mirrors the Anthropic Messages API's tool definition:
 * a name, a JSON input schema, and an {@link #invoke} method that
 * takes the resolved input and returns a text result the model sees as
 * the {@code tool_result} content block on the next turn.
 *
 * <p>B4 scope is read-only tools (file IO + glob). Mutating tools
 * (write, edit, shell) land alongside a permission-gate hook in B5 so
 * the user keeps the same approve / deny experience the CLI lane has.
 */
public interface AgentTool
{
    /** Provider-facing name. The Anthropic Messages API matches by
     *  this string; keep it lowercase + underscores so it's a stable
     *  identifier across catalog updates. */
    String name();

    /** Short one-line description the model uses to decide whether to
     *  call this tool. Keep it action-oriented. */
    String description();

    /** Tool input schema in the JSON-Schema shape Anthropic expects:
     *  {@code {"type":"object","properties":{...},"required":[...]}}.
     *  The agent forwards it verbatim in the {@code tools} array of
     *  the request. */
    JsonNode inputSchema();

    /** Whether this tool can fire without explicit user approval.
     *  Read-only tools (ReadFile, Glob, Ls) are auto-allow; mutating
     *  tools should return {@code false} so the LogicLoopThreadAgent
     *  routes them through the permission gate before invoking. */
    boolean isReadOnly();

    /** Run the tool. {@code input} is the parsed JSON object the model
     *  passed; {@code ctx} carries the working dir + session-scoped
     *  state. Implementations should not throw on tool-level failures
     *  — return a {@link Result#error result with isError=true} so the
     *  model can correct course on the next turn. */
    Result invoke(JsonNode input, AgentToolContext ctx);

    /** Tool outcome. {@code text} is what the model sees as the
     *  {@code tool_result} content block; {@code isError} marks
     *  recoverable failures so the renderer can show them inline. */
    record Result(String text, boolean isError)
    {
        public static Result ok(String text)
        {
            return new Result(text, false);
        }

        public static Result error(String message)
        {
            return new Result(message, true);
        }
    }
}
