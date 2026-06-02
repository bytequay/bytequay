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

/**
 * Deny envelope serialised inside a {@link ToolCallResult}'s text
 * payload — Claude Code reads this and ends the turn with the message
 * rendered as the tool's response.
 */
public record DenyEnvelope(String behavior, String message)
{
    public static DenyEnvelope of(String message)
    {
        return new DenyEnvelope("deny", message);
    }
}
