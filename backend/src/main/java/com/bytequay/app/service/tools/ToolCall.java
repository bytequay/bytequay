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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lane-neutral context for one tool invocation. Both the MCP server
 * and the future in-JVM lane build a {@code ToolCall} and hand it to
 * {@link AgentToolRegistry#invoke}; the handler reads the thread /
 * role from here and gets its typed args bound separately from
 * {@link #arguments}.
 *
 * @param threadId   the thread the call runs in
 * @param arguments  the raw JSON arguments the model emitted — the
 *                   registry binds these into the handler's typed
 *                   args record, but handlers can also read them
 *                   directly when a field isn't on the record
 * @param role       the caller's resolved agent role
 */
public record ToolCall(String threadId, JsonNode arguments, AgentRole role) {}
