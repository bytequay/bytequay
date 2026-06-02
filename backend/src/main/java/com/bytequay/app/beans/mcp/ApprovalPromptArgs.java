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
package com.bytequay.app.beans.mcp;

import com.bytequay.app.service.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Args record for the MCP {@code approval_prompt} tool — Claude's
 * {@code --permission-prompt-tool} target. The {@link JsonProperty}
 * annotations make the record directly bindable via Jackson
 * {@code treeToValue} so the controller's hand-coded dispatch reads
 * fields by accessor instead of {@code args.path("tool_name")}.
 */
public record ApprovalPromptArgs(
        @ToolParam(description = "The tool the agent is asking permission for.",
                required = true, wireName = "tool_name")
        @JsonProperty("tool_name") String toolName,
        @ToolParam(description = "JSON object of the arguments the agent wants to invoke the tool with.",
                required = true) JsonNode input,
        @ToolParam(description = "Opaque correlation id the CLI uses to match the response back to the pending call.",
                required = true, wireName = "tool_use_id")
        @JsonProperty("tool_use_id") String toolUseId) {}
