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

import com.fasterxml.jackson.databind.JsonNode;

/** One entry in a {@link ListToolsResult}: the tool's name,
 *  description, and generated input schema (parsed from the registry's
 *  string form into a tree so it nests cleanly in the response). */
public record ToolDescriptor(String name, String description, JsonNode inputSchema) {}
