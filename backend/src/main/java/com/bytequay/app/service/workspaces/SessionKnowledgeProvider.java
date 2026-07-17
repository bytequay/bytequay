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
package com.bytequay.app.service.workspaces;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Small, cycle-free read path used when an agent Session starts. It combines
 * the compatibility brain mirror with only KB rows tagged for that Session's
 * public audience.
 */
@Service
public class SessionKnowledgeProvider
{
    private static final Set<String> AUDIENCES = Set.of(
            "plan", "dev", "review", "ci-fix");
    private static final TypeReference<List<String>> STRINGS =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SessionKnowledgeProvider(JdbcTemplate jdbc, ObjectMapper mapper)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public String render(String workspaceId, String audience)
    {
        if (workspaceId == null || workspaceId.isBlank()
                || audience == null || !AUDIENCES.contains(audience)) {
            return "";
        }
        String brain = jdbc.query("""
                SELECT memory_md FROM workspaces WHERE id = ?
                """, rs -> rs.next() ? rs.getString(1) : "", workspaceId);
        if (!audienceEnabled(workspaceId, audience)) {
            return brain == null ? "" : brain;
        }

        List<Entry> entries = jdbc.query("""
                SELECT title, body, audience_json
                FROM kb_entry
                WHERE workspace_id = ?
                ORDER BY updated_at_ms DESC, id
                """, (rs, ignored) -> new Entry(
                rs.getString("title"),
                rs.getString("body"),
                strings(rs.getString("audience_json"))), workspaceId);
        StringBuilder out = new StringBuilder(brain == null ? "" : brain.strip());
        boolean heading = false;
        for (Entry entry : entries) {
            if (!entry.audience().contains(audience)) {
                continue;
            }
            if (!heading) {
                if (!out.isEmpty()) out.append("\n\n");
                out.append("# Knowledge base (").append(audience).append(")");
                heading = true;
            }
            out.append("\n\n## ").append(entry.title()).append("\n\n")
                    .append(entry.body().strip());
        }
        return out.toString();
    }

    private boolean audienceEnabled(String workspaceId, String audience)
    {
        List<String> rows = jdbc.queryForList("""
                SELECT settings_json FROM workspace_settings
                WHERE workspace_id = ?
                """, String.class, workspaceId);
        if (rows.isEmpty()) {
            return true;
        }
        try {
            JsonNode node = mapper.readTree(rows.getFirst()).path("kbAudiences");
            if (!node.isArray()) {
                return true;
            }
            for (JsonNode value : node) {
                if (audience.equals(value.asText())) {
                    return true;
                }
            }
            return false;
        }
        catch (Exception ignored) {
            return true;
        }
    }

    private List<String> strings(String json)
    {
        try {
            return mapper.readValue(json, STRINGS);
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private record Entry(String title, String body, List<String> audience) {}
}
