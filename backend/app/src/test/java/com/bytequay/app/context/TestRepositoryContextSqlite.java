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

import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises notebook serialization and retention through the application's real SQLite settings port. */
@SpringBootTest
class TestRepositoryContextSqlite
{
    @Autowired
    private RepositoryContextService context;

    @Autowired
    private AppSettingsStore settings;

    @Autowired
    private WorkspaceStore workspaces;

    @Autowired
    private AgentReviewStore reviews;

    @Autowired
    private ObjectMapper mapper;

    private String workspaceId;

    @BeforeEach
    void createWorkspace()
    {
        UUID identity = UUID.randomUUID();
        workspaceId = identity.toString();
        long repositoryId = Math.max(1, identity.getMostSignificantBits() & Long.MAX_VALUE);
        RepoRef repository = RepoRef.of("context-test", "repository-" + workspaceId);
        workspaces.createOrGet(new Workspace(
                workspaceId, "Context test", repositoryId, repository, Optional.empty(), Optional.empty(), repositoryId, repository,
                Workspace.Topology.DIRECT, "main", "https://github.com/" + repository.fullName() + ".git", Optional.empty(), Optional.empty(),
                "/tmp/repository-context-test/" + workspaceId, Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void absentNotebookDefaultsOnWithoutCreatingASettingsRow()
    {
        assertThat(context.getNotebook(workspaceId)).isEqualTo(RepositoryNotebook.empty());
        assertThat(settings.get(key())).isEmpty();
    }

    @Test
    void notebookSurvivesServiceRecreationAndUnwatchWithOptimisticEdits()
    {
        ContextScope scope = new ContextScope("path:plugin/iceberg/", "Iceberg",
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of("iceberg")), true);
        context.configure(workspaceId, 0, new EditContextConfiguration(
                Optional.of(false),
                Optional.of(ImmutableList.of(new EditContextConfiguration.Source(
                        "iceberg-docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://iceberg.apache.org/docs/latest/"))),
                Optional.of(ImmutableList.of(ContextScope.project(), scope))));
        RepositoryNotebook saved = context.editEntry(workspaceId, 1, new EditContextEntry(
                ContextEntry.knowledgeId(scope.id()), scope.id(), "Connector questions", "Questions to investigate", "Check the snapshot contract.",
                ImmutableList.of(new ContextClaim("Which snapshot is visible?", ContextClaim.Basis.QUESTION, ImmutableList.of())), ImmutableList.of()));

        workspaces.setWatched(workspaceId, false);
        RepositoryContextService recreated = new RepositoryContextService(settings, mapper, workspaces, reviews);

        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(saved);
        assertThat(recreated.getNotebook(workspaceId).useInReviews()).isFalse();
        assertThatThrownBy(() -> recreated.setUseInReviews(workspaceId, 1, true)).isInstanceOf(ContextConflictException.class);
        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(saved);
        assertThat(recreated.setUseInReviews(workspaceId, saved.revision(), true).entries()).isEqualTo(saved.entries());
    }

    @Test
    void corruptSavedDataIsNotReplacedByReadOrMutation()
    {
        String corrupt = "{\"schemaVersion\":1,\"entries\":[";
        settings.put(key(), corrupt);

        assertThatThrownBy(() -> context.getNotebook(workspaceId)).isInstanceOf(ContextStorageException.class);
        assertThatThrownBy(() -> context.setUseInReviews(workspaceId, 0, false)).isInstanceOf(ContextStorageException.class);
        assertThat(settings.get(key())).contains(corrupt);
    }

    @Test
    void explicitDeletionRemovesOnlyTheOwningNotebook()
    {
        context.setUseInReviews(workspaceId, 0, false);
        settings.put("workspace.repositoryContext.unrelated", "retained");

        context.deleteWorkspaceData(workspaceId);

        assertThat(settings.get(key())).isEmpty();
        assertThat(settings.get("workspace.repositoryContext.unrelated")).contains("retained");
    }

    private String key()
    {
        return "workspace.repositoryContext." + workspaceId;
    }
}
