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

import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Links saved on the workspace that names them, seen from both ends. Fork links also form repo lines: a workspace's
 * parent is its default Fork source, or its only one.
 */
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

    public record Relationship(String workspaceId, Kind kind, String guidance, boolean backportByDefault)
    {
        public Relationship
        {
            checkArgument(workspaceId != null && !workspaceId.isBlank() && workspaceId.length() <= 100, "A workspace ID is required");
            checkArgument(kind != null, "Choose Fork or Dependency");
            checkArgument(guidance != null && guidance.length() <= 500, "Guidance must be at most 500 characters");
            guidance = guidance.strip();
            checkArgument(!backportByDefault || kind == Kind.FORK, "Only a Fork can be a default backport source");
        }
    }

    /** Where another workspace sits relative to this one. */
    public enum Position
    {
        UPSTREAM, DOWNSTREAM, DEPENDENCY, DEPENDENT;

        public Kind kind()
        {
            return this == UPSTREAM || this == DOWNSTREAM ? Kind.FORK : Kind.DEPENDENCY;
        }
    }

    public record Related(String workspaceId, Position position, String guidance) {}

    public record LineNode(String workspaceId, Optional<String> parentWorkspaceId) {}

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

    public record BackportWorkspace(String workspaceId, String name, String repository, List<Relationship> sources)
    {
        public BackportWorkspace
        {
            sources = ImmutableList.copyOf(sources);
        }
    }

    public List<BackportWorkspace> backportWorkspaces()
    {
        return workspaces.findAllRetained().stream().filter(workspace -> workspaces.isWatched(workspace.id()))
                .map(workspace -> new BackportWorkspace(workspace.id(), workspace.name(), workspace.monitoredRepository().fullName(),
                        list(workspace.id()).stream().filter(link -> link.kind() == Kind.FORK).collect(toImmutableList())))
                .collect(toImmutableList());
    }

    public List<Relationship> save(String workspaceId, Relationship relationship)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            Workspace source = requireWorkspace(workspaces, workspaceId);
            Workspace destination = requireWorkspace(workspaces, relationship.workspaceId());
            checkArgument(workspaces.isWatched(destination.id()), "Choose a watched workspace");
            requireDifferent(source, destination);
            List<Relationship> existing = list(workspaceId);
            checkArgument(!relationship.backportByDefault() || existing.stream().noneMatch(other -> other.backportByDefault()
                            && !other.workspaceId().equals(relationship.workspaceId())),
                    "Choose one default backport source; turn off the existing default first");
            for (Relationship other : existing) {
                if (!other.workspaceId().equals(destination.id())) {
                    checkArgument(workspaces.findById(other.workspaceId())
                                    .filter(value -> value.monitoredRepositoryId() == destination.monitoredRepositoryId()).isEmpty(),
                            "This project is already related through another workspace");
                }
            }
            if (relationship.backportByDefault()) {
                var visited = new HashSet<String>();
                visited.add(workspaceId);
                Optional<String> upstream = Optional.of(relationship.workspaceId());
                while (upstream.isPresent()) {
                    String current = upstream.orElseThrow();
                    checkArgument(visited.add(current), "Default backport sources cannot form a cycle");
                    upstream = list(current).stream().filter(Relationship::backportByDefault).map(Relationship::workspaceId).findFirst();
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

    /** This workspace's own links, links other workspaces saved to it, then the rest of its repo line. */
    public List<Related> related(String workspaceId)
    {
        requireWorkspace(workspaces, workspaceId);
        Map<String, List<Relationship>> links = watchedLinks();
        Map<String, Related> result = new LinkedHashMap<>();
        for (Relationship link : list(workspaceId)) {
            result.put(link.workspaceId(), new Related(link.workspaceId(), link.kind() == Kind.FORK ? Position.UPSTREAM : Position.DEPENDENCY, link.guidance()));
        }
        links.forEach((other, theirs) -> theirs.stream().filter(link -> link.workspaceId().equals(workspaceId)).findFirst()
                .ifPresent(link -> result.putIfAbsent(other, new Related(other, link.kind() == Kind.FORK ? Position.DOWNSTREAM : Position.DEPENDENT, ""))));
        boolean upstream = true;
        for (LineNode node : line(workspaceId, links)) {
            if (node.workspaceId().equals(workspaceId)) {
                upstream = false;
            }
            else {
                result.putIfAbsent(node.workspaceId(), new Related(node.workspaceId(), upstream ? Position.UPSTREAM : Position.DOWNSTREAM, ""));
            }
        }
        return ImmutableList.copyOf(result.values());
    }

    /** Upstream ancestors oldest first, this workspace, then everything downstream of it; siblings stay out. */
    public List<LineNode> line(String workspaceId)
    {
        requireWorkspace(workspaces, workspaceId);
        return line(workspaceId, watchedLinks());
    }

    /** By source workspace, the remotes a checkout fetches to read and cherry-pick from: every upstream in its line and every Fork it lists. */
    public Map<String, RemoteSource> lineRemotes(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Map<String, List<Relationship>> links = watchedLinks();
        Set<String> sources = new LinkedHashSet<>();
        for (LineNode node : line(workspaceId, links)) {
            if (node.workspaceId().equals(workspaceId)) {
                break;
            }
            sources.add(node.workspaceId());
        }
        list(workspaceId).stream().filter(link -> link.kind() == Kind.FORK && links.containsKey(link.workspaceId())).forEach(link -> sources.add(link.workspaceId()));
        Map<String, RemoteSource> remotes = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (String id : sources) {
            Workspace source = requireWorkspace(workspaces, id);
            String name = remoteName(source.monitoredRepositoryId());
            if (source.monitoredRepositoryId() != workspace.monitoredRepositoryId() && names.add(name)) {
                remotes.put(id, new RemoteSource(workspaceId, name, source.upstreamCloneUrl().orElse(source.originCloneUrl()),
                        Optional.of(source.monitoredRepositoryId()), Optional.of(source.monitoredRepository())));
            }
        }
        return ImmutableMap.copyOf(remotes);
    }

    public static String remoteName(long repositoryId)
    {
        return "backport-" + repositoryId;
    }

    /** Rechecked on each tool call. */
    public Workspace requireRelated(String workspaceId, String destinationId)
    {
        checkArgument(related(workspaceId).stream().anyMatch(value -> value.workspaceId().equals(destinationId)), "Workspace is not related");
        return requireReadable(workspaceId, destinationId);
    }

    /** Agent conversations may read any other watched project; a link only adds guidance and the right to start work there. */
    public Workspace requireReadable(String workspaceId, String destinationId)
    {
        Workspace destination = requireWorkspace(workspaces, destinationId);
        checkArgument(workspaces.isWatched(destinationId), "Related workspace is unavailable");
        requireDifferent(requireWorkspace(workspaces, workspaceId), destination);
        return destination;
    }

    /** Watched workspaces of other projects that {@link #related} does not list. */
    public List<Workspace> unrelatedWatched(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Set<String> related = related(workspaceId).stream().map(Related::workspaceId).collect(toImmutableSet());
        return workspaces.findAllRetained().stream()
                .filter(other -> workspaces.isWatched(other.id()) && !related.contains(other.id()) && other.monitoredRepositoryId() != workspace.monitoredRepositoryId()
                        && !matches(other, workspace.monitoredRepository()))
                .collect(toImmutableList());
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

    private List<LineNode> line(String workspaceId, Map<String, List<Relationship>> links)
    {
        List<String> upstream = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(workspaceId);
        for (Optional<String> parent = parent(workspaceId, links); parent.isPresent() && seen.add(parent.orElseThrow()); parent = parent(parent.orElseThrow(), links)) {
            upstream.addFirst(parent.orElseThrow());
        }
        ImmutableList.Builder<LineNode> nodes = ImmutableList.builder();
        for (int index = 0; index < upstream.size(); index++) {
            nodes.add(new LineNode(upstream.get(index), index == 0 ? Optional.empty() : Optional.of(upstream.get(index - 1))));
        }
        nodes.add(new LineNode(workspaceId, upstream.isEmpty() ? Optional.empty() : Optional.of(upstream.getLast())));
        addDownstream(workspaceId, links, seen, nodes);
        return nodes.build();
    }

    private static void addDownstream(String workspaceId, Map<String, List<Relationship>> links, Set<String> seen, ImmutableList.Builder<LineNode> nodes)
    {
        for (String child : links.keySet()) {
            if (parent(child, links).filter(workspaceId::equals).isPresent() && seen.add(child)) {
                nodes.add(new LineNode(child, Optional.of(workspaceId)));
                addDownstream(child, links, seen, nodes);
            }
        }
    }

    private static Optional<String> parent(String workspaceId, Map<String, List<Relationship>> links)
    {
        List<Relationship> forks = links.getOrDefault(workspaceId, ImmutableList.of()).stream()
                .filter(link -> link.kind() == Kind.FORK && links.containsKey(link.workspaceId()))
                .collect(toImmutableList());
        return forks.stream().filter(Relationship::backportByDefault).findFirst()
                .or(() -> forks.size() == 1 ? Optional.of(forks.getFirst()) : Optional.empty())
                .map(Relationship::workspaceId);
    }

    private Map<String, List<Relationship>> watchedLinks()
    {
        // ponytail: reads every watched workspace's links per call; cache them if workspace catalogs grow large.
        return workspaces.findAllRetained().stream().filter(workspace -> workspaces.isWatched(workspace.id()))
                .collect(toImmutableMap(Workspace::id, workspace -> list(workspace.id())));
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
