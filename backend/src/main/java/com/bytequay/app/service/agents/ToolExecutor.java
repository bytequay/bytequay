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
package com.bytequay.app.service.agents;

/**
 * Caller-supplied tool semantics for a {@link TurnRunner} turn. The
 * runner parses tool calls off the provider stream and hands each one
 * here; the executor decides what actually happens — registry
 * dispatch, permission gating, budget checks, or a structured refusal.
 * Implementations must not throw on tool-level failures: return
 * {@link ToolCallResult#error} so the model can correct course.
 */
public interface ToolExecutor
{
    ToolCallResult execute(ToolCall call);

    /** What the model sees as the tool's result on the next round. */
    record ToolCallResult(String text, boolean isError)
    {
        public static ToolCallResult ok(String text)
        {
            return new ToolCallResult(text, false);
        }

        public static ToolCallResult error(String message)
        {
            return new ToolCallResult(message, true);
        }
    }
}
