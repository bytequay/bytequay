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
package com.bytequay.app.service.threads.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * Read a UTF-8 file relative to the session's working dir. Sandboxed
 * — refuses to read outside the working dir. Truncates at 64 KB so a
 * misclick on a huge file doesn't blow up the next model turn's
 * context budget.
 */
@Component
public class ReadFileTool
        implements AgentTool
{
    private static final int MAX_READ_BYTES = 64 * 1024;

    private final ObjectMapper mapper;

    public ReadFileTool(ObjectMapper mapper)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public String name()
    {
        return "read_file";
    }

    @Override
    public String description()
    {
        return "Read a UTF-8 text file at the given path relative to the working directory. "
                + "Returns the file contents truncated at " + MAX_READ_BYTES + " bytes.";
    }

    @Override
    public JsonNode inputSchema()
    {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode path = mapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "Relative path to the file from the working directory.");
        props.set("path", path);
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("path"));
        return schema;
    }

    @Override
    public boolean isReadOnly()
    {
        return true;
    }

    @Override
    public Result invoke(JsonNode input, AgentToolContext ctx)
    {
        String requested = input == null ? null : input.path("path").asText(null);
        if (requested == null || requested.isBlank()) {
            return Result.error("read_file requires a non-empty `path`.");
        }
        Path workingDir = ctx.workingDir();
        if (workingDir == null) {
            return Result.error("Session has no working directory; cannot read files.");
        }
        Path resolved;
        try {
            resolved = workingDir.resolve(requested).normalize();
        }
        catch (RuntimeException e) {
            return Result.error("Invalid path: " + requested);
        }
        // Refuse paths that escape the working dir — the model can
        // arrive at "../../etc/passwd" from a confused prompt, and we
        // don't ever want the JVM serving a file outside the session
        // sandbox even on the read path.
        if (!resolved.startsWith(workingDir)) {
            return Result.error("Path escapes the working directory.");
        }
        if (!Files.exists(resolved)) {
            return Result.error("No such file: " + requested);
        }
        if (!Files.isRegularFile(resolved)) {
            return Result.error("Not a regular file: " + requested);
        }
        try {
            byte[] bytes = Files.readAllBytes(resolved);
            if (bytes.length > MAX_READ_BYTES) {
                String head = new String(bytes, 0, MAX_READ_BYTES, StandardCharsets.UTF_8);
                return Result.ok(head + "\n\n…truncated at " + MAX_READ_BYTES + " bytes…");
            }
            return Result.ok(new String(bytes, StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            return Result.error("Read failed: " + e.getMessage());
        }
    }
}
