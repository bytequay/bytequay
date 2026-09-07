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

import com.bytequay.core.review.ReviewConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON columns of the review tables; the domain records are the wire shape, read back through their own validation. */
final class AgentReviewJson
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private AgentReviewJson() {}

    static String encode(Object value)
    {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new ReviewConflictException("Cannot serialize review data", e);
        }
    }

    static <T> T decode(String json, TypeReference<T> type)
    {
        try {
            return OBJECT_MAPPER.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new ReviewConflictException("Cannot deserialize review data", e);
        }
    }

    static <T> T decode(String json, Class<T> type)
    {
        try {
            return OBJECT_MAPPER.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new ReviewConflictException("Cannot deserialize review data", e);
        }
    }
}
