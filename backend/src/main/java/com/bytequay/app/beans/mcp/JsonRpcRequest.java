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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Top-level JSON-RPC request envelope. {@code id} stays {@link JsonNode}
 * because JSON-RPC ids may be string, number, or null; {@code params}
 * stays {@link JsonNode} because the params shape is method-specific
 * and gets bound further inside each handler.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonRpcRequest(String jsonrpc, String method, JsonNode id, JsonNode params) {}
