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

import com.bytequay.persistence.sqlite.converter.JsonColumns;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.REVIEW;

/** JSON columns of the review tables; the domain records are the wire shape, read back through their own validation. */
final class AgentReviewJson
{
    // Stored runs keep fields the review record has since dropped, such as summary and coverage limitations.
    private static final ObjectMapper OBJECT_MAPPER = JsonColumns.mapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AgentReviewJson() {}

    static String encode(Object value)
    {
        return REVIEW.guard("Cannot serialize review data", () -> OBJECT_MAPPER.writeValueAsString(value));
    }

    static <T> T decode(String json, TypeReference<T> type)
    {
        return REVIEW.guard("Cannot deserialize review data", () -> OBJECT_MAPPER.readValue(json, type));
    }

    static <T> T decode(String json, Class<T> type)
    {
        return REVIEW.guard("Cannot deserialize review data", () -> OBJECT_MAPPER.readValue(json, type));
    }
}
