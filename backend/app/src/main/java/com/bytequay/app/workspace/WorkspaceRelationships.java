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
package com.bytequay.app.workspace;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Direct context links, independent of Git contribution routing. */
@Service
public class WorkspaceRelationships
{
    private static final String PREFIX = "workspace.relationships.";
    private final WorkspaceStore workspaces;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public WorkspaceRelationships(WorkspaceStore workspaces, AppSettingsStore settings, ObjectMapper mapper)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public enum Kind { FORK, DEPENDENCY }

    public record Relationship(String workspaceId, Kind kind, String guidance)
    {
        public Relationship
        {
            checkArgument(workspaceId != null && !workspaceId.isBlank() && workspaceId.length() <= 100, "A workspace ID is required");
            checkArgument(kind != null, "Choose Fork or Dependency");
            checkArgument(guidance != null && guidance.length() <= 500, "Guidance must be at most 500 characters");
            guidance = guidance.strip();
        }
    }

    public List<Relationship> list(String workspaceId)
    {
        requireWorkspace(workspaces, workspaceId);
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                return ImmutableList.copyOf(mapper.readValue(json, new TypeReference<List<Relationship>>() {}));
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Cannot read related workspaces", failure);
            }
        }).orElse(ImmutableList.of());
    }

    public List<Relationship> save(String workspaceId, Relationship relationship)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            Workspace source = requireWorkspace(workspaces, workspaceId);
            Workspace destination = requireWorkspace(workspaces, relationship.workspaceId());
            checkArgument(workspaces.isWatched(destination.id()), "Choose a watched workspace");
            requireDifferent(source, destination);
            List<Relationship> existing = list(workspaceId);
            for (Relationship other : existing) {
                if (!other.workspaceId().equals(destination.id())) {
                    checkArgument(workspaces.findById(other.workspaceId())
                                    .filter(value -> value.monitoredRepositoryId() == destination.monitoredRepositoryId()).isEmpty(),
                            "This project is already related through another workspace");
                }
            }
            List<Relationship> next = ImmutableList.<Relationship>builder()
                    .addAll(existing.stream().filter(value -> !value.workspaceId().equals(destination.id())).collect(toImmutableList()))
                    .add(relationship).build();
            persist(workspaceId, next);
            return next;
        });
    }

    public List<Relationship> remove(String workspaceId, String destinationId)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            List<Relationship> next = list(workspaceId).stream().filter(value -> !value.workspaceId().equals(destinationId)).collect(toImmutableList());
            persist(workspaceId, next);
            return next;
        });
    }

    /** Rechecked on each tool call; never follows the destination's own links. */
    public Workspace requireRelated(String workspaceId, String destinationId)
    {
        checkArgument(list(workspaceId).stream().anyMatch(value -> value.workspaceId().equals(destinationId)), "Workspace is not directly related");
        Workspace destination = requireWorkspace(workspaces, destinationId);
        checkArgument(workspaces.isWatched(destinationId), "Related workspace is unavailable");
        requireDifferent(requireWorkspace(workspaces, workspaceId), destination);
        return destination;
    }

    public Optional<Workspace> available(String workspaceId, String destinationId)
    {
        try {
            return Optional.of(requireRelated(workspaceId, destinationId));
        }
        catch (IllegalArgumentException | WorkspaceException failure) {
            return Optional.empty();
        }
    }

    /** Apply to both directions and all remote aliases, including known personal push forks. */
    public void requireTransferAllowed(RepoRef source, RepoRef target)
    {
        requireTransferAllowed(Optional.of(source), target);
    }

    /** Old executions lack a source snapshot. Do not infer it from a mutable remote when a dependency could be involved. */
    public void requireTransferAllowed(Optional<RepoRef> source, RepoRef target)
    {
        // ponytail: scan local settings; index repository pairs if large workspace catalogs make admission slow.
        for (Workspace workspace : workspaces.findAllRetained()) {
            if (source.isPresent() && !matches(workspace, source.orElseThrow()) && !matches(workspace, target)) {
                continue;
            }
            for (Relationship relationship : list(workspace.id())) {
                if (relationship.kind() == Kind.DEPENDENCY) {
                    workspaces.findById(relationship.workspaceId()).ifPresent(destination -> {
                        checkArgument(source.isPresent() || !matches(workspace, target) && !matches(destination, target),
                                "This execution has no saved source repository. Preview again before delivering to a workspace with a dependency");
                        checkArgument(!(source.filter(value -> matches(workspace, value)).isPresent() && matches(destination, target)
                                        || matches(workspace, target) && source.filter(value -> matches(destination, value)).isPresent()),
                                "Dependency workspaces cannot cherry-pick or backport between each other");
                    });
                }
            }
        }
    }

    private static boolean matches(Workspace workspace, RepoRef repository)
    {
        return workspace.monitoredRepository().fullName().equalsIgnoreCase(repository.fullName())
                || workspace.selectedRepository().fullName().equalsIgnoreCase(repository.fullName());
    }

    private static void requireDifferent(Workspace source, Workspace destination)
    {
        checkArgument(!source.id().equals(destination.id()), "A workspace cannot relate to itself");
        checkArgument(source.monitoredRepositoryId() != destination.monitoredRepositoryId(),
                "Both workspaces monitor the same project. Check their upstream settings; relationships do not change PR routing");
    }

    private void persist(String workspaceId, List<Relationship> relationships)
    {
        try {
            settings.put(PREFIX + workspaceId, mapper.writeValueAsString(relationships));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot save related workspaces", failure);
        }
    }
}
