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

import com.bytequay.agent.core.AgentAttachmentReference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;

final class AgentAttachmentReferenceJson
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<AgentAttachmentReference>> REFERENCE_LIST = new TypeReference<>() {};

    private AgentAttachmentReferenceJson() {}

    public static String encode(List<AgentAttachmentReference> references)
    {
        return AGENT.guard("Cannot serialize turn attachment references", () -> OBJECT_MAPPER.writeValueAsString(references));
    }

    public static List<AgentAttachmentReference> decode(String json)
    {
        return AGENT.guard("Cannot deserialize turn attachment references", () -> ImmutableList.copyOf(OBJECT_MAPPER.readValue(json, REFERENCE_LIST)));
    }
}
