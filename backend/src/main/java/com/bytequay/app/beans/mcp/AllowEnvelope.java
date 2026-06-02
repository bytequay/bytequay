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
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Allow envelope serialised inside a {@link ToolCallResult}'s text
 * payload — Claude Code reads this to know the permission prompt is
 * approved and which input to use.
 */
public record AllowEnvelope(String behavior, JsonNode updatedInput)
{
    /** Build an allow envelope, defaulting a missing {@code
     *  updatedInput} to an empty object so the wire shape never carries
     *  a JSON {@code null} where Claude expects a parameters object. */
    public static AllowEnvelope of(ObjectMapper mapper, JsonNode updatedInput)
    {
        JsonNode normalised = (updatedInput == null || updatedInput.isMissingNode())
                ? mapper.createObjectNode()
                : updatedInput;
        return new AllowEnvelope("allow", normalised);
    }
}
