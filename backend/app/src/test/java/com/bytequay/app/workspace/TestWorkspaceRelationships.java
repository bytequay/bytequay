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

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.bytequay.app.workspace.WorkspaceRelationships.Kind.DEPENDENCY;
import static com.bytequay.app.workspace.WorkspaceRelationships.Kind.FORK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorkspaceRelationships
{
    private final InMemoryWorkspaceStore workspaces = new InMemoryWorkspaceStore();
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final WorkspaceRelationships relationships = new WorkspaceRelationships(workspaces, settings, new ObjectMapper());

    @Test
    void persistsOnlyDirectLinksAndRechecksAvailabilityAndRouting()
    {
        Workspace p2 = add("p2", 2);
        Workspace p3 = add("p3", 3);
        add("p4", 4);
        relationships.save("p2", new WorkspaceRelationships.Relationship("p3", FORK, "Our private additions"));
        relationships.save("p3", new WorkspaceRelationships.Relationship("p4", FORK, "Upstream behavior"));
        assertThat(relationships.requireRelated("p2", "p3")).isEqualTo(p3);
        assertThatThrownBy(() -> relationships.requireRelated("p2", "p4")).hasMessageContaining("not directly related");
        assertThatThrownBy(() -> relationships.requireRelated("p3", "p2")).hasMessageContaining("not directly related");
        assertThatThrownBy(() -> relationships.save("p2", new WorkspaceRelationships.Relationship("p2", FORK, "")))
                .hasMessageContaining("itself");
        Workspace alias = add("alias", 3);
        assertThatThrownBy(() -> relationships.save("p2", new WorkspaceRelationships.Relationship(alias.id(), FORK, "")))
                .hasMessageContaining("already related");
        assertThatThrownBy(() -> relationships.save("p3", new WorkspaceRelationships.Relationship(alias.id(), FORK, "")))
                .hasMessageContaining("upstream settings");
        relationships.save("p2", new WorkspaceRelationships.Relationship("p3", DEPENDENCY, "Version in pom.xml"));
        assertThat(new WorkspaceRelationships(workspaces, settings, new ObjectMapper()).list("p2")).singleElement()
                .satisfies(link -> assertThat(link.guidance()).isEqualTo("Version in pom.xml"));
        assertThat(workspaces.findById("p2")).contains(p2);
        workspaces.setWatched("p3", false);
        assertThat(relationships.available("p2", "p3")).isEmpty();
        assertThatThrownBy(() -> relationships.requireRelated("p2", "p3")).hasMessageContaining("unavailable");
        relationships.remove("p2", "p3");
        assertThat(relationships.list("p2")).isEmpty();
    }

    @Test
    void dependencyBlocksBothDirectionsAndKnownPushForksWhileOtherManualRoutesStayAllowed()
    {
        Workspace trino = add("trino", 1);
        Workspace iceberg = add("iceberg", 2);
        Workspace push = add("iceberg-push", 3);
        workspaces.save(push.withRepositoryRouting(push.selectedRepository(), Optional.of(2L), Optional.of(iceberg.monitoredRepository()),
                2, iceberg.monitoredRepository(), "main", push.originCloneUrl(), Optional.of("main"), Optional.of(iceberg.originCloneUrl()), Instant.EPOCH));
        relationships.save("trino", new WorkspaceRelationships.Relationship("iceberg-push", DEPENDENCY, "Library"));
        assertThatThrownBy(() -> relationships.requireTransferAllowed(trino.monitoredRepository(), iceberg.monitoredRepository()))
                .hasMessageContaining("cannot cherry-pick");
        assertThatThrownBy(() -> relationships.requireTransferAllowed(push.selectedRepository(), trino.monitoredRepository()))
                .hasMessageContaining("cannot cherry-pick");
        assertThatThrownBy(() -> relationships.requireTransferAllowed(Optional.empty(), trino.monitoredRepository()))
                .hasMessageContaining("no saved source repository");
        relationships.requireTransferAllowed(Optional.empty(), RepoRef.of("other", "project"));
        relationships.requireTransferAllowed(RepoRef.of("other", "project"), trino.monitoredRepository());
        relationships.save("trino", new WorkspaceRelationships.Relationship("iceberg-push", FORK, "Fork"));
        relationships.requireTransferAllowed(push.selectedRepository(), trino.monitoredRepository());
    }

    @Test
    void concurrentRowSavesDoNotLoseOtherRelationships()
    {
        add("p2", 2);
        add("p3", 3);
        add("p4", 4);
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> relationships.save("p2", new WorkspaceRelationships.Relationship("p3", FORK, ""))),
                CompletableFuture.runAsync(() -> relationships.save("p2", new WorkspaceRelationships.Relationship("p4", FORK, ""))))
                .join();
        assertThat(relationships.list("p2")).extracting(WorkspaceRelationships.Relationship::workspaceId).containsExactlyInAnyOrder("p3", "p4");
    }

    private Workspace add(String id, long repositoryId)
    {
        Workspace workspace = new Workspace(id, id, repositoryId, RepoRef.of("owner", id), Optional.empty(), Optional.empty(),
                repositoryId, RepoRef.of("owner", id), Workspace.Topology.DIRECT, "main", "https://github.com/owner/" + id + ".git",
                Optional.empty(), Optional.empty(), "/managed/" + id, Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH);
        return workspaces.save(workspace);
    }
}
