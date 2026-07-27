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
package com.bytequay.app.beans.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One pull-request preparation/publication milestone from a task's local
 * development work. It is projected from {@code PULL_REQUEST_PROGRESS} and
 * {@code PULL_REQUEST_CREATED} stage events into both the Development
 * transcript and the task brain feed.
 */
public record PullRequestCreatedData(
        String phase,
        String branch,
        String baseBranch,
        int number,
        String url,
        int additions,
        int deletions,
        String failedStep,
        String reason)
{
    /**
     * Decodes the versioned stage-event payload. A malformed historical
     * payload remains a visible generic event rather than breaking the whole
     * conversation response.
     */
    public static PullRequestCreatedData fromPayload(String payloadJson, ObjectMapper mapper)
    {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(payloadJson);
            return new PullRequestCreatedData(
                    phase(node),
                    text(node, "branch"),
                    text(node, "baseBranch"),
                    node.path("number").asInt(0),
                    text(node, "url"),
                    node.path("additions").asInt(0),
                    node.path("deletions").asInt(0),
                    text(node, "failedStep"),
                    text(node, "reason"));
        }
        catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String phase(JsonNode node)
    {
        String phase = text(node, "phase");
        return phase == null ? "created" : phase;
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
