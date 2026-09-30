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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** The latest spec check of each plan topic. */
@Component
public class ContextSpecCheckStore
{
    static final String PREFIX = "workspace.repositoryContextSpecChecks.";
    private static final int MAX_STORED_BYTES = 4 * 1024 * 1024;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public ContextSpecCheckStore(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public List<ContextSpecCheck> find(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= MAX_STORED_BYTES, "Saved spec checks exceed 4 MiB");
                return ImmutableList.copyOf(mapper.readValue(json, ContextSpecCheck[].class));
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
                throw new ContextStorageException("Saved spec checks cannot be read", failure);
            }
        }).orElse(ImmutableList.of());
    }

    public Optional<ContextSpecCheck> find(String workspaceId, String checkId)
    {
        return find(workspaceId).stream().filter(check -> check.id().equals(checkId)).findFirst();
    }

    /** At most one check runs at a time: it holds the workspace's one learning task. */
    public Optional<ContextSpecCheck> active(String workspaceId)
    {
        return find(workspaceId).stream().filter(check -> check.state().active()).findFirst();
    }

    /** Replaces the topic's check when the saved one still equals what the caller read; a new check replaces the topic's last one. */
    public ContextSpecCheck save(String workspaceId, Optional<ContextSpecCheck> expected, ContextSpecCheck replacement)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            List<ContextSpecCheck> current = find(workspaceId);
            Optional<ContextSpecCheck> saved = current.stream().filter(check -> check.topicId().equals(replacement.topicId())).findFirst();
            checkArgument(saved.equals(expected), "Spec check changed; inspect the latest check");
            List<ContextSpecCheck> updated = Stream.concat(current.stream().filter(check -> !check.topicId().equals(replacement.topicId())), Stream.of(replacement))
                    .collect(toImmutableList());
            try {
                String json = mapper.writeValueAsString(updated);
                checkArgument(ContextLimits.bytes(json) <= MAX_STORED_BYTES, "Spec checks exceed 4 MiB");
                settings.put(PREFIX + workspaceId, json);
                return replacement;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Spec checks cannot be saved", failure);
            }
        });
    }
}
