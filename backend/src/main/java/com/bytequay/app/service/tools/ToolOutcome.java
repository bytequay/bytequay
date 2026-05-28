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

/**
 * Lane-neutral result of a tool handler. The handler describes what
 * should happen; the lane (MCP server today, in-JVM runner later)
 * adapts it to its own transport — DeferredResult + approval gate for
 * MCP, the turn loop for the in-JVM lane. This keeps the safety model
 * (park / gate) in the lane while the handler stays a pure function
 * of its inputs.
 *
 * <p>Only {@link Completed} exists today, because only the AUTO read
 * tools have migrated onto the registry-dispatch path. Park / approval
 * variants land alongside the publishers and run_shell when those
 * tools migrate — until then they keep their hand-coded handlers in
 * the MCP controller.
 */
public sealed interface ToolOutcome
{
    /**
     * An AUTO tool's immediate result. For a success the lane echoes
     * {@code text} back to the model verbatim (it is already a JSON
     * string); for {@code isError} the lane wraps {@code text} as the
     * deny message so the model reads it as a recoverable tool failure
     * rather than a hard protocol error.
     */
    record Completed(String text, boolean isError)
            implements ToolOutcome
    {
        public static Completed ok(String text)
        {
            return new Completed(text, false);
        }

        public static Completed error(String message)
        {
            return new Completed(message, true);
        }
    }
}
