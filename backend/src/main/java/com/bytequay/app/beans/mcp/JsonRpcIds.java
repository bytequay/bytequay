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

/** Shared id-normalisation for {@link JsonRpcSuccess} and
 *  {@link JsonRpcError}. JSON-RPC ids can be string, number, or null;
 *  Spring deserialises a missing id field as
 *  {@link JsonNode#isMissingNode() missing}, which we collapse to a
 *  Java {@code null} so {@code @JsonInclude(NON_NULL)} drops it from
 *  the response. */
final class JsonRpcIds
{
    private JsonRpcIds() {}

    static JsonNode normalise(JsonNode id)
    {
        return (id == null || id.isMissingNode()) ? null : id;
    }
}
