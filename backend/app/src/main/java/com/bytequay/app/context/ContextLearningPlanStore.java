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

/** Latest accepted topic plan and source inventory; per-turn execution still belongs to the agent task store. */
@Component
public class ContextLearningPlanStore
{
    static final String PREFIX = "workspace.repositoryContextLearningPlan.";
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public ContextLearningPlanStore(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public Optional<ContextLearningPlan> find(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Saved learning plan exceeds 1 MiB");
                JsonNode stored = mapper.readTree(json);
                JsonNode inventory = stored.path("documentInventory");
                if (inventory instanceof ObjectNode object && !object.has("suggestedExpansionUrls")) {
                    object.putArray("suggestedExpansionUrls");
                }
                ContextLearningPlan plan = requireNonNull(mapper.treeToValue(stored, ContextLearningPlan.class), "Saved learning plan is null");
                checkArgument(plan.workspaceId().equals(workspaceId), "Saved learning plan belongs to another workspace");
                return plan;
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
                throw new ContextStorageException("Saved learning plan cannot be read", failure);
            }
        });
    }

    public void delete(ContextLearningPlan expected)
    {
        WorkspaceOperationLocks.withLock(expected.workspaceId(), () -> {
            checkArgument(find(expected.workspaceId()).equals(Optional.of(expected)), "Learning plan changed; inspect latest progress");
            settings.delete(PREFIX + expected.workspaceId());
            return null;
        });
    }

    public ContextLearningPlan save(Optional<ContextLearningPlan> expected, ContextLearningPlan replacement)
    {
        return WorkspaceOperationLocks.withLock(replacement.workspaceId(), () -> {
            checkArgument(find(replacement.workspaceId()).equals(expected), "Learning plan changed; inspect the latest progress");
            try {
                String json = mapper.writeValueAsString(replacement);
                checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Learning plan exceeds 1 MiB");
                settings.put(PREFIX + replacement.workspaceId(), json);
                return replacement;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Learning plan cannot be saved", failure);
            }
        });
    }
}
