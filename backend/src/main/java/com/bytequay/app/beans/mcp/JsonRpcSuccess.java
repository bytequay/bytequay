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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC success envelope. {@code id} is null-omitted so the
 * response to an id-less notification doesn't carry a phantom field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcSuccess(String jsonrpc, JsonNode id, Object result)
{
    /** Build a {@code "2.0"} success envelope and normalise a missing
     *  id to {@code null} so {@link JsonInclude} drops it. */
    public static JsonRpcSuccess of(JsonNode id, Object result)
    {
        return new JsonRpcSuccess("2.0", JsonRpcIds.normalise(id), result);
    }
}
