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

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/** How many web lookups each finished development turn made, recorded once when the turn settles, for the 1000 most recent turns. */
@Component
public class ContextWebLookups
{
    static final String PREFIX = "workspace.repositoryContextWebLookups.";
    static final int MAX_TURNS = 1000;

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public ContextWebLookups(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public List<Turn> find(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Saved web lookup counts exceed 1 MiB");
                return ImmutableList.copyOf(mapper.readValue(json, Turn[].class));
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
                throw new ContextStorageException("Saved web lookup counts cannot be read", failure);
            }
        }).orElse(ImmutableList.of());
    }

    /** Keeps a turn's first count: a replayed settle can see only part of a long turn. */
    public void record(String workspaceId, Turn turn)
    {
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            List<Turn> current = find(workspaceId);
            if (current.stream().anyMatch(saved -> saved.turnId().equals(turn.turnId()))) {
                return null;
            }
            List<Turn> kept = Stream.concat(Stream.of(turn), current.stream())
                    .sorted(comparing(Turn::settledAt).reversed()).limit(MAX_TURNS).collect(toImmutableList());
            try {
                settings.put(PREFIX + workspaceId, mapper.writeValueAsString(kept));
                return null;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Web lookup counts cannot be saved", failure);
            }
        });
    }

    public record Turn(String taskId, String turnId, int lookups, Instant settledAt)
    {
        public Turn
        {
            ContextLimits.text(taskId, "taskId", 200);
            ContextLimits.text(turnId, "turnId", 200);
            checkArgument(lookups >= 0, "Negative web lookup count");
            requireNonNull(settledAt, "settledAt is null");
        }
    }
}
