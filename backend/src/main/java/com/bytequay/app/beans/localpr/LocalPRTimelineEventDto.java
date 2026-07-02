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
package com.bytequay.app.beans.localpr;

import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * Wire shape of a {@link LocalPRTimelineEvent}. The stored {@code payloadJson}
 * text is parsed to a {@code payload} object so the frontend receives a
 * structured value, not a JSON string.
 */
public record LocalPRTimelineEventDto(
        String id,
        String localPrId,
        String eventType,
        String actor,
        boolean isLocalOnly,
        Long strippedOnPushAt,
        long createdAt,
        JsonNode payload)
{
    public static LocalPRTimelineEventDto from(LocalPRTimelineEvent e, ObjectMapper mapper)
    {
        return new LocalPRTimelineEventDto(
                e.id(),
                e.localPrId(),
                e.eventType(),
                e.actor(),
                e.localOnly(),
                e.strippedOnPushAt() == null ? null : e.strippedOnPushAt().toEpochMilli(),
                e.createdAt().toEpochMilli(),
                parse(e.payloadJson(), mapper));
    }

    private static JsonNode parse(String json, ObjectMapper mapper)
    {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return mapper.readTree(json);
        }
        catch (JsonProcessingException e) {
            // A malformed payload shouldn't 500 the timeline read — surface null.
            return NullNode.getInstance();
        }
    }
}
