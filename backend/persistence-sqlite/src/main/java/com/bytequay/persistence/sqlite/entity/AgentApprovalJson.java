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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.persistence.sqlite.converter.JsonColumns;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;

final class AgentApprovalJson
{
    private static final ObjectMapper MAPPER = JsonColumns.mapper();

    private AgentApprovalJson() {}

    public static String encode(ApprovalRequest request)
    {
        return AGENT.guard("Cannot serialize approval request", () -> MAPPER.writeValueAsString(request));
    }

    public static ApprovalRequest decode(String json)
    {
        return AGENT.guard("Cannot deserialize approval request", () -> MAPPER.readValue(json, ApprovalRequest.class));
    }
}
