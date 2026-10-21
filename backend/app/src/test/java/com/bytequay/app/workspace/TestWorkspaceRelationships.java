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
    void persistsLinksOnTheNamingWorkspaceAndRechecksAvailabilityAndRouting()
    {
        Workspace p2 = add("p2", 2);
        Workspace p3 = add("p3", 3);
        add("p4", 4);
        add("p5", 5);
        relationships.save("p2", new WorkspaceRelationships.Relationship("p3", FORK, "Our private additions", false));
        relationships.save("p3", new WorkspaceRelationships.Relationship("p4", FORK, "Upstream behavior", false));
        assertThat(relationships.list("p3")).extracting(WorkspaceRelationships.Relationship::workspaceId).containsExactly("p4");
        assertThat(relationships.requireRelated("p2", "p3")).isEqualTo(p3);
        assertThat(relationships.requireRelated("p2", "p4").id()).isEqualTo("p4");
        assertThat(relationships.requireRelated("p3", "p2")).isEqualTo(p2);
        assertThatThrownBy(() -> relationships.requireRelated("p2", "p5")).hasMessageContaining("not related");
        assertThatThrownBy(() -> relationships.save("p2", new WorkspaceRelationships.Relationship("p2", FORK, "", false)))
                .hasMessageContaining("itself");
        Workspace alias = add("alias", 3);
        assertThatThrownBy(() -> relationships.save("p2", new WorkspaceRelationships.Relationship(alias.id(), FORK, "", false)))
                .hasMessageContaining("already related");
        assertThatThrownBy(() -> relationships.save("p3", new WorkspaceRelationships.Relationship(alias.id(), FORK, "", false)))
                .hasMessageContaining("upstream settings");
        relationships.save("p2", new WorkspaceRelationships.Relationship("p3", DEPENDENCY, "Version in pom.xml", false));
        assertThat(new WorkspaceRelationships(workspaces, settings, new ObjectMapper()).list("p2")).singleElement()
                .satisfies(link -> assertThat(link.guidance()).isEqualTo("Version in pom.xml"));
        assertThat(workspaces.findById("p2")).contains(p2);
        workspaces.setWatched("p3", false);
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
        relationships.save("trino", new WorkspaceRelationships.Relationship("iceberg-push", DEPENDENCY, "Library", false));
        assertThatThrownBy(() -> relationships.requireTransferAllowed(trino.monitoredRepository(), iceberg.monitoredRepository()))
                .hasMessageContaining("cannot cherry-pick");
        assertThatThrownBy(() -> relationships.requireTransferAllowed(push.selectedRepository(), trino.monitoredRepository()))
                .hasMessageContaining("cannot cherry-pick");
        assertThatThrownBy(() -> relationships.requireTransferAllowed(Optional.empty(), trino.monitoredRepository()))
                .hasMessageContaining("no saved source repository");
        relationships.requireTransferAllowed(Optional.empty(), RepoRef.of("other", "project"));
        relationships.requireTransferAllowed(RepoRef.of("other", "project"), trino.monitoredRepository());
        relationships.save("trino", new WorkspaceRelationships.Relationship("iceberg-push", FORK, "Fork", false));
        relationships.requireTransferAllowed(push.selectedRepository(), trino.monitoredRepository());
    }

    @Test
    void defaultBackportSourceIsExplicitUniqueAndAcyclic()
    {
        add("oss", 1);
        add("internal", 2);
        add("private", 3);
        relationships.save("internal", new WorkspaceRelationships.Relationship("oss", FORK, "", true));
        relationships.save("private", new WorkspaceRelationships.Relationship("internal", FORK, "", true));
        assertThat(relationships.list("private").getFirst().backportByDefault()).isTrue();
        assertThat(relationships.backportWorkspaces()).hasSize(3);
        assertThatThrownBy(() -> relationships.save("private", new WorkspaceRelationships.Relationship("oss", FORK, "", true)))
                .hasMessageContaining("one default");
        assertThatThrownBy(() -> relationships.save("oss", new WorkspaceRelationships.Relationship("private", FORK, "", true)))
                .hasMessageContaining("cycle");
        assertThatThrownBy(() -> new WorkspaceRelationships.Relationship("oss", DEPENDENCY, "", true)).hasMessageContaining("Only a Fork");
        relationships.save("private", new WorkspaceRelationships.Relationship("internal", FORK, "", false));
        relationships.save("private", new WorkspaceRelationships.Relationship("oss", FORK, "", true));
        assertThat(relationships.backportWorkspaces().stream().filter(workspace -> workspace.workspaceId().equals("private")).findFirst().orElseThrow().sources())
                .anySatisfy(link -> {
                    assertThat(link.workspaceId()).isEqualTo("oss");
                    assertThat(link.backportByDefault()).isTrue();
                });
    }

    @Test
    void repoLineFollowsEachWorkspaceParentAndLeavesSiblingsOut()
    {
        add("oss", 1);
        add("internal", 2);
        add("product", 3);
        add("sibling", 4);
        add("lib", 5);
        relationships.save("internal", new WorkspaceRelationships.Relationship("oss", FORK, "Internal fork", false));
        relationships.save("product", new WorkspaceRelationships.Relationship("internal", FORK, "Product", false));
        relationships.save("product", new WorkspaceRelationships.Relationship("lib", DEPENDENCY, "Library", false));
        relationships.save("sibling", new WorkspaceRelationships.Relationship("oss", FORK, "", false));

        assertThat(relationships.line("product")).containsExactly(
                new WorkspaceRelationships.LineNode("oss", Optional.empty()),
                new WorkspaceRelationships.LineNode("internal", Optional.of("oss")),
                new WorkspaceRelationships.LineNode("product", Optional.of("internal")));
        assertThat(relationships.line("oss")).extracting(WorkspaceRelationships.LineNode::workspaceId).containsExactly("oss", "internal", "product", "sibling");
        assertThat(relationships.line("internal")).extracting(WorkspaceRelationships.LineNode::workspaceId).containsExactly("oss", "internal", "product");
        assertThat(relationships.related("product")).containsExactly(
                new WorkspaceRelationships.Related("internal", WorkspaceRelationships.Position.UPSTREAM, "Product"),
                new WorkspaceRelationships.Related("lib", WorkspaceRelationships.Position.DEPENDENCY, "Library"),
                new WorkspaceRelationships.Related("oss", WorkspaceRelationships.Position.UPSTREAM, ""));
        assertThat(relationships.related("lib")).containsExactly(new WorkspaceRelationships.Related("product", WorkspaceRelationships.Position.DEPENDENT, ""));
        assertThatThrownBy(() -> relationships.requireRelated("product", "sibling")).hasMessageContaining("not related");
        assertThat(relationships.lineRemotes("product")).containsOnlyKeys("oss", "internal")
                .hasEntrySatisfying("oss", remote -> {
                    assertThat(remote.name()).isEqualTo("backport-1");
                    assertThat(remote.url()).isEqualTo("https://github.com/owner/oss.git");
                    assertThat(remote.repository()).contains(RepoRef.of("owner", "oss"));
                });
        assertThat(relationships.lineRemotes("oss")).isEmpty();

        // Two Fork sources without a default leave the line ambiguous, so it stops there.
        relationships.save("product", new WorkspaceRelationships.Relationship("sibling", FORK, "", false));
        assertThat(relationships.line("product")).extracting(WorkspaceRelationships.LineNode::workspaceId).containsExactly("product");
        assertThat(relationships.lineRemotes("product")).containsOnlyKeys("internal", "sibling");
        relationships.save("product", new WorkspaceRelationships.Relationship("internal", FORK, "Product", true));
        assertThat(relationships.line("product")).extracting(WorkspaceRelationships.LineNode::workspaceId).containsExactly("oss", "internal", "product");
        workspaces.setWatched("internal", false);
        assertThat(relationships.line("product")).extracting(WorkspaceRelationships.LineNode::workspaceId).containsExactly("oss", "sibling", "product");
    }

    @Test
    void concurrentRowSavesDoNotLoseOtherRelationships()
    {
        add("p2", 2);
        add("p3", 3);
        add("p4", 4);
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> relationships.save("p2", new WorkspaceRelationships.Relationship("p3", FORK, "", false))),
                CompletableFuture.runAsync(() -> relationships.save("p2", new WorkspaceRelationships.Relationship("p4", FORK, "", false))))
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
