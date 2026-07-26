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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Reads the engine picks the workspace settings page writes — the
 * {@code providers} map on the workspace settings row, keyed by session
 * audience ({@code plan} / {@code dev} / {@code review} / {@code ci-fix})
 * plus a {@code default} entry the roles fall back to.
 *
 * <p>Values are the picker's choice ids: {@code cli:<agent>},
 * {@code api:<provider>:<account>}, or {@code local} for the ds4 server.
 *
 * <p>Deliberately JDBC-direct instead of calling
 * {@code WorkspaceConfigurationService}: that service reaches
 * {@code ThreadRegistry} through {@code SessionControlService}, and the
 * registry depends on {@link WorkModelResolver} — going through it would
 * close a bean cycle.
 */
@Component
public class WorkspaceEngineSettings
{
    /** Settings key holding the workspace-wide pick every role inherits. */
    public static final String DEFAULT_KEY = "default";
    /** The ds4-served local model the {@code local} choice id maps to. */
    private static final String LOCAL_PROVIDER = "deepseek";
    private static final String LOCAL_MODEL = "deepseek-v4-flash";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkspaceEngineSettings(JdbcTemplate jdbc, ObjectMapper mapper)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** A configured engine plus whether it came from the audience's own row
     *  rather than the workspace default — the provenance label says
     *  "workspace X · dev" only for the former. */
    public record Engine(WorkModel model, boolean fromRole) {}

    /**
     * The engine configured for one session audience: the role's own pick
     * when set, else the workspace default. Empty when the workspace has
     * configured neither, so the caller falls back to the curated default.
     */
    public Optional<Engine> forAudience(String workspaceId, String audience)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            return Optional.empty();
        }
        JsonNode providers = readProviders(workspaceId);
        if (providers == null) {
            return Optional.empty();
        }
        return choice(providers, audience)
                .map(model -> new Engine(model, true))
                .or(() -> choice(providers, DEFAULT_KEY).map(model -> new Engine(model, false)));
    }

    private JsonNode readProviders(String workspaceId)
    {
        List<String> rows = jdbc.queryForList("""
                SELECT settings_json
                FROM workspace_settings
                WHERE workspace_id = ?
                """, String.class, workspaceId);
        if (rows.isEmpty()) {
            return null;
        }
        try {
            JsonNode providers = mapper.readTree(rows.getFirst()).get("providers");
            return providers == null || !providers.isObject() ? null : providers;
        }
        catch (JsonProcessingException e) {
            // A settings row we can't parse isn't worth failing a turn over —
            // the caller falls back to the curated default.
            return null;
        }
    }

    private static Optional<WorkModel> choice(JsonNode providers, String key)
    {
        JsonNode value = key == null ? null : providers.get(key);
        return value == null || !value.isTextual()
                ? Optional.empty()
                : parseChoice(value.asText());
    }

    /**
     * Map one picker choice id onto a work model. An unrecognised id
     * resolves to empty rather than a guess — the caller then falls back
     * to the workspace default or the curated global default.
     */
    public static Optional<WorkModel> parseChoice(String choice)
    {
        if (choice == null || choice.isBlank()) {
            return Optional.empty();
        }
        String value = choice.strip();
        if (value.equals("local")) {
            return Optional.of(new WorkModel(
                    WorkModelKind.API, LOCAL_PROVIDER, LOCAL_MODEL, null));
        }
        String[] parts = value.split(":", 3);
        if (parts.length >= 2 && parts[0].equals("cli") && !parts[1].isBlank()) {
            // No model id: the CLI keeps using whichever model it defaults to.
            return Optional.of(new WorkModel(
                    WorkModelKind.CLI, parts[1], null, null));
        }
        if (parts.length >= 2 && parts[0].equals("api") && !parts[1].isBlank()) {
            String account = parts.length == 3 && !parts[2].isBlank() ? parts[2] : null;
            return Optional.of(new WorkModel(
                    WorkModelKind.API, parts[1], null, account));
        }
        return Optional.empty();
    }
}
