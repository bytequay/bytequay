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
package com.bytequay.app.context;

import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The latest discovery proposal survives subsequent learning builds; proposals do not activate notes. */
@Component
public class ContextDiscoveryReportStore
{
    static final String PREFIX = "workspace.repositoryContextDiscoveryReport.";
    private static final int MAX_REPORT_BYTES = 4 * 1024 * 1024;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public ContextDiscoveryReportStore(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public Optional<ContextDiscoveryReport> find(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= MAX_REPORT_BYTES, "Saved discovery report exceeds 4 MiB");
                JsonNode value = requireNonNull(mapper.readTree(json), "Saved discovery report is null");
                // Old suggestions remain inspectable, but unknown applicability must never be inferred from their source paths.
                value.path("topics").forEach(topic -> {
                    if (topic instanceof ObjectNode object && !object.has("notePaths")) {
                        object.putNull("notePaths");
                    }
                });
                ContextDiscoveryReport report = mapper.treeToValue(value, ContextDiscoveryReport.class);
                checkArgument(report.workspaceId().equals(workspaceId), "Saved discovery report belongs to another workspace");
                return report;
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
                throw new ContextStorageException("Saved discovery report cannot be read", failure);
            }
        });
    }

    public ContextDiscoveryReport save(Optional<ContextDiscoveryReport> expected, ContextDiscoveryReport replacement)
    {
        return WorkspaceOperationLocks.withLock(replacement.workspaceId(), () -> {
            checkArgument(find(replacement.workspaceId()).equals(expected), "Discovery report changed; inspect the latest result");
            try {
                String json = mapper.writeValueAsString(replacement);
                checkArgument(ContextLimits.bytes(json) <= MAX_REPORT_BYTES, "Discovery report exceeds 4 MiB");
                settings.put(PREFIX + replacement.workspaceId(), json);
                return replacement;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Discovery report cannot be saved", failure);
            }
        });
    }
}
