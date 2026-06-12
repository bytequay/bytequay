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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.AgendaPhase;
import com.bytequay.app.domain.AgendaPhaseStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * (De)serialises {@code review_passes.agenda_json} — a JSON array of
 * {@code {id, title, status}} objects, status in its lowercase
 * storage form. Tolerant on read: malformed rows decode to an empty
 * agenda rather than failing a transcript poll.
 */
final class AgendaJsonCodec
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgendaJsonCodec() {}

    static List<AgendaPhase> parse(String agendaJson)
    {
        if (agendaJson == null || agendaJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(agendaJson);
        }
        catch (IOException e) {
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }
        List<AgendaPhase> out = new ArrayList<>();
        for (JsonNode node : root) {
            String id = node.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            out.add(new AgendaPhase(
                    id,
                    node.path("title").asText(id),
                    AgendaPhaseStatus.fromJsonValue(node.path("status").asText(""))));
        }
        return List.copyOf(out);
    }

    static String write(List<AgendaPhase> agenda)
    {
        ArrayNode arr = MAPPER.createArrayNode();
        for (AgendaPhase phase : agenda) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", phase.id());
            node.put("title", phase.title());
            node.put("status", phase.status().jsonValue());
            arr.add(node);
        }
        return arr.toString();
    }

    /** Copy with one phase's status changed; no-op when the id is
     *  unknown (idempotent mark-phase semantics). */
    static List<AgendaPhase> withStatus(List<AgendaPhase> agenda, String phaseId, AgendaPhaseStatus status)
    {
        List<AgendaPhase> out = new ArrayList<>(agenda.size());
        for (AgendaPhase phase : agenda) {
            out.add(phase.id().equals(phaseId)
                    ? new AgendaPhase(phase.id(), phase.title(), status)
                    : phase);
        }
        return List.copyOf(out);
    }
}
