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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One tool call the model requested during a {@link TurnRunner} round
 * — parsed off the stream but NOT executed by the runner. The caller's
 * {@link ToolExecutor} decides what (if anything) happens.
 *
 * @param id           provider-assigned call id, echoed back on the
 *                     result so the model can stitch them together.
 * @param name         tool name as the model requested it.
 * @param rawArguments the accumulated argument string exactly as it
 *                     streamed (the OpenAI follow-up echo requires the
 *                     verbatim text, not a re-serialisation).
 * @param input        the arguments parsed as JSON; an empty object
 *                     when the raw text was empty or malformed.
 */
public record ToolCall(
        String id,
        String name,
        String rawArguments,
        JsonNode input)
{
}
