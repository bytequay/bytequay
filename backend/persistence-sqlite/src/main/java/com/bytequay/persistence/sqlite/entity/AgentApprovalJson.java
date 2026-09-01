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

import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.ApprovalRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

final class AgentApprovalJson
{
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private AgentApprovalJson() {}

    public static String encode(ApprovalRequest request)
    {
        try {
            return MAPPER.writeValueAsString(request);
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw storageException("Cannot serialize approval request", e);
        }
    }

    public static ApprovalRequest decode(String json)
    {
        try {
            return MAPPER.readValue(json, ApprovalRequest.class);
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw storageException("Cannot deserialize approval request", e);
        }
    }

    private static AgentStorageException storageException(String message, Exception failure)
    {
        if (failure instanceof AgentStorageException storageException) {
            return storageException;
        }
        return new AgentStorageException(message, failure);
    }
}
