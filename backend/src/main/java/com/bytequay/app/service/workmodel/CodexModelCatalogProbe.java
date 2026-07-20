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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/** Reads the picker-visible model catalog from the installed Codex CLI. */
@Component
public class CodexModelCatalogProbe
{
    private static final Logger log = LoggerFactory.getLogger(CodexModelCatalogProbe.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final int PAGE_SIZE = 100;

    private final ObjectMapper json;
    private List<Model> cached;

    public CodexModelCatalogProbe(ObjectMapper json)
    {
        this.json = requireNonNull(json, "json is null");
    }

    /**
     * Returns the last successfully discovered catalog. A refresh attempts a
     * new app-server read but keeps the last good result if that read fails.
     */
    public synchronized Optional<List<Model>> models(boolean refresh)
    {
        if (!refresh && cached != null) {
            return Optional.of(cached);
        }

        Optional<List<Model>> discovered = discover();
        if (discovered.isPresent()) {
            cached = discovered.orElseThrow();
            return discovered;
        }
        return Optional.ofNullable(cached);
    }

    private Optional<List<Model>> discover()
    {
        Process process = null;
        FutureTask<Optional<List<Model>>> readTask = null;
        try {
            process = new ProcessBuilder("codex", "app-server")
                    // Some CLI builds emit startup warnings on stderr. Merge
                    // and ignore non-JSON lines so neither pipe can fill.
                    .redirectErrorStream(true)
                    .start();
            Process running = process;
            readTask = new FutureTask<>(() -> exchange(running));
            Thread.ofVirtual().name("codex-model-catalog").start(readTask);
            return readTask.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (Exception e) {
            log.debug("Could not read models from codex app-server: {}", e.getMessage());
            return Optional.empty();
        }
        finally {
            if (readTask != null && !readTask.isDone()) {
                readTask.cancel(true);
            }
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private Optional<List<Model>> exchange(Process process)
            throws Exception
    {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
            ObjectNode initialize = request(1, "initialize");
            initialize.withObject("params")
                    .withObject("clientInfo")
                    .put("name", "bytequay")
                    .put("title", "ByteQuay")
                    .put("version", "1");
            write(writer, initialize);

            ObjectNode initialized = json.createObjectNode();
            initialized.put("method", "initialized");
            initialized.set("params", json.createObjectNode());
            write(writer, initialized);

            int requestId = 2;
            write(writer, modelListRequest(requestId, null));
            List<Model> models = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode response;
                try {
                    response = json.readTree(line);
                }
                catch (Exception ignored) {
                    continue;
                }
                if (response.path("id").asInt(-1) != requestId || !response.has("result")) {
                    continue;
                }

                Page page = parsePage(response.path("result"));
                models.addAll(page.models());
                if (page.nextCursor() == null) {
                    return models.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(models));
                }
                requestId++;
                write(writer, modelListRequest(requestId, page.nextCursor()));
            }
            return Optional.empty();
        }
    }

    private ObjectNode request(int id, String method)
    {
        ObjectNode request = json.createObjectNode();
        request.put("method", method);
        request.put("id", id);
        request.set("params", json.createObjectNode());
        return request;
    }

    private ObjectNode modelListRequest(int id, String cursor)
    {
        ObjectNode request = request(id, "model/list");
        ObjectNode params = request.withObject("params");
        params.put("includeHidden", false);
        params.put("limit", PAGE_SIZE);
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        return request;
    }

    private void write(BufferedWriter writer, JsonNode message)
            throws Exception
    {
        writer.write(json.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }

    static Page parsePage(JsonNode result)
    {
        List<Model> models = new ArrayList<>();
        for (JsonNode item : result.path("data")) {
            if (item.path("hidden").asBoolean(false)) {
                continue;
            }
            String id = text(item, "model");
            if (id == null) {
                id = text(item, "id");
            }
            if (id == null) {
                continue;
            }

            List<ReasoningEffort> efforts = new ArrayList<>();
            for (JsonNode effort : item.path("supportedReasoningEfforts")) {
                String effortId = text(effort, "reasoningEffort");
                if (effortId != null) {
                    efforts.add(new ReasoningEffort(effortId, text(effort, "description")));
                }
            }
            String displayName = text(item, "displayName");
            models.add(new Model(
                    id,
                    displayName == null ? id : displayName,
                    text(item, "description"),
                    item.path("isDefault").asBoolean(false),
                    text(item, "defaultReasoningEffort"),
                    List.copyOf(efforts)));
        }
        return new Page(List.copyOf(models), text(result, "nextCursor"));
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            return null;
        }
        return value.textValue();
    }

    public record Model(
            String id,
            String displayName,
            String description,
            boolean isDefault,
            String defaultReasoningEffort,
            List<ReasoningEffort> supportedReasoningEfforts) {}

    public record ReasoningEffort(String id, String description) {}

    record Page(List<Model> models, String nextCursor) {}
}
