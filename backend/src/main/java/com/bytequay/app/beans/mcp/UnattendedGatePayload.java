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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Wire shape for the {@code NEEDS_ATTENTION} notification emitted when
 * an unattended turn requests a tool outside its autonomy envelope.
 * {@code reason} is a fixed discriminator so the dashboard can route
 * on it.
 */
@JsonPropertyOrder({"reason", "tool", "summary"})
public record UnattendedGatePayload(String tool, String summary)
{
    @JsonProperty("reason") public String reason()
    {
        return "unattended turn requested an out-of-bounds tool";
    }
}
