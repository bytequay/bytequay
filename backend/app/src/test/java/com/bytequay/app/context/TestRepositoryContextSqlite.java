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

import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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

    @Autowired
    private AgentUnitOfWork unitOfWork;

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
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of("iceberg")), true, Optional.empty());
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
    void preparationReceiptsSurviveReloadAndPreferenceChangesIncludingBlankSources()
            throws Exception
    {
        String sha = "a".repeat(40);
        String readId = "context-read:" + UUID.randomUUID();
        ContextReference.RepositoryFile reference = new ContextReference.RepositoryFile(1, RepoRef.of("example", "repository"), "README.md", sha);
        ContextRepositoryRead read = new ContextRepositoryRead(readId, reference, sha, Instant.EPOCH, 1, 1, 1, 6, false, Optional.of(readId));
        ContextSourceEvidence evidence = new ContextSourceEvidence(readId, reference, "Captured baseline source", "source", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.of(sha), Optional.empty(), false);
        RegisteredContextSource documentation = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/docs/page", ContextEntryOrigin.USER);
        ContextDocumentRead blank = new ContextDocumentRead("context-read:" + UUID.randomUUID(), new ContextReference.Document("docs", documentation.url()),
                documentation, 0, UUID.randomUUID().toString(), "b".repeat(64), Instant.EPOCH, 1, 1, 0, true, false, false, Optional.empty());
        ContextInputFingerprint input = ContextInputFingerprint.create("project", ImmutableMap.of("git:README.md", "100644 blob " + sha), ImmutableList.of());
        ContextPreparationResult result = new ContextPreparationResult("completed-attempt", input, ContextPreparationResult.Outcome.EMPTY, ImmutableList.of(),
                ImmutableList.of(read, blank), "The inspected sources did not establish a useful note");
        ContextScope scope = new ContextScope("project", "Project", new ContextMatch.Repository(), true, Optional.of(result));
        ActiveContextPreparation active = new ActiveContextPreparation("active-attempt", "request", "sha256:" + "a".repeat(64),
                "task", "turn", "turn-key", "review", "run", 0, ImmutableList.of(input));
        RepositoryNotebook notebook = new RepositoryNotebook(1, 0, true, ImmutableList.of(documentation), ImmutableList.of(scope), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(evidence), Optional.of(active));
        settings.put(key(), mapper.writeValueAsString(notebook));

        RepositoryContextService recreated = new RepositoryContextService(settings, mapper, workspaces, reviews);
        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(notebook);
        RepositoryNotebook disabled = recreated.setUseInReviews(workspaceId, 0, false);
        assertThat(disabled.revision()).isEqualTo(1);
        assertThat(disabled.scopes()).containsExactly(scope);
        assertThat(disabled.sourceEvidence()).containsExactly(evidence);
        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(disabled);
    }

    @Test
    void notebookAndReviewReceiptRollbackTogetherWhenRunWriteFails()
    {
        String sha = "a".repeat(40);
        ReviewRun run = reviews.insertRun(new ReviewRun(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, ReviewRun.Scope.FULL,
                "main", sha, sha, sha, Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING,
                Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(),
                Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty()));
        ContextInputFingerprint input = ContextInputFingerprint.create("project", ImmutableMap.of("git:README.md", "100644 blob " + sha), ImmutableList.of());
        ActiveContextPreparation active = new ActiveContextPreparation("attempt", "request", "sha256:" + "a".repeat(64),
                "task", "turn", "turn-key", run.reviewId(), run.id(), 0, ImmutableList.of(input));
        RepositoryNotebook empty = context.getNotebook(workspaceId);
        RepositoryNotebook owned = new RepositoryNotebook(1, 0, true, empty.sources(), empty.scopes(), empty.entries(),
                empty.suppressedKnowledgeIds(), empty.sourceEvidence(), Optional.of(active));
        ReviewOperation receipt = new ReviewOperation("a".repeat(64), "{\"status\":\"OWNED\"}");

        assertThatThrownBy(() -> WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            context.savePreparation(workspaceId, empty, owned);
            reviews.updateRun(run.withOperation("begin", receipt, Instant.EPOCH).withVersion(run.version() + 1));
            return null;
        }))).isInstanceOf(RuntimeException.class);
        assertThat(context.getNotebook(workspaceId)).isEqualTo(empty);
        assertThat(reviews.findRun(run.id()).orElseThrow()).isEqualTo(run);

        WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            context.savePreparation(workspaceId, empty, owned);
            reviews.updateRun(run.withOperation("begin", receipt, Instant.EPOCH));
            return null;
        }));
        assertThat(context.getNotebook(workspaceId)).isEqualTo(owned);
        assertThat(reviews.findRun(run.id()).orElseThrow().operations()).containsEntry("begin", receipt);
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
