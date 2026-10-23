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

import com.bytequay.app.git.CherryPickPullRequestLookup;
import com.bytequay.app.git.PullRequestAssociation;
import com.bytequay.app.git.PullRequestLookupResult;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.CommitHistoryStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSelectedCommitPullRequestReader
{
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final String FIRST = "a".repeat(40);
    private static final String SECOND = "b".repeat(40);
    private static final String THIRD = "c".repeat(40);
    private static final RepoRef MONITORED = RepoRef.of("owner", "project");
    private static final Path REPOSITORY = Path.of("/tmp/selected-commit-reader");
    private static final long REPOSITORY_ID = 42;

    private CommitHistoryStore enrichment;
    private GitCommandRunner git;
    private CherryPickPullRequestLookup lookup;
    private SelectedCommitPullRequestReader reader;

    @BeforeEach
    void setUp()
    {
        enrichment = mock(CommitHistoryStore.class);
        git = mock(GitCommandRunner.class);
        lookup = mock(CherryPickPullRequestLookup.class);
        reader = new SelectedCommitPullRequestReader(enrichment, git, lookup, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reusesPositiveAssociationsWithoutGitOrProviderEvenWhenLegacyCheckTimeIsAbsent()
    {
        CommitEnrichment cached = stored(REPOSITORY_ID, FIRST, Optional.of(12), Optional.empty());
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST))).thenReturn(snapshot(cached));

        SelectedCommitPullRequestReader.Read read = reader.read(workspace(false), ImmutableList.of(FIRST.toUpperCase()));

        assertThat(read.items()).containsExactly(new SelectedCommitPullRequestReader.Item(FIRST, Optional.of(12), Optional.of("Pull request 12"),
                Optional.of("https://github.com/owner/project/pull/12"), Optional.empty(), false, false, true));
        assertThat(read.gaps()).isEmpty();
        verifyNoInteractions(git, lookup);
        verify(enrichment, never()).upsertEnrichment(any());
    }

    @Test
    void defersFreshAndOldCheckedNegativesAndRetriesDueRecentAndUncheckedOldCommits()
    {
        String fourth = "d".repeat(64);
        List<String> selected = ImmutableList.of(FIRST, SECOND, THIRD, fourth);
        CommitEnrichment fresh = stored(REPOSITORY_ID, FIRST, Optional.empty(), Optional.of(NOW.minusSeconds(60)));
        CommitEnrichment old = stored(REPOSITORY_ID, SECOND, Optional.empty(), Optional.of(NOW.minus(Duration.ofDays(2))));
        CommitEnrichment due = stored(REPOSITORY_ID, THIRD, Optional.empty(), Optional.of(NOW.minus(Duration.ofDays(1))));
        when(enrichment.findEnrichment(REPOSITORY_ID, selected)).thenReturn(snapshot(fresh, old, due));
        metadata(FIRST, NOW.minus(Duration.ofDays(1)));
        metadata(SECOND, NOW.minus(Duration.ofDays(8)));
        metadata(THIRD, NOW.minus(Duration.ofDays(7)));
        metadata(fourth, NOW.minus(Duration.ofDays(100)));
        when(lookup.lookUpResult(eq(MONITORED), eq(ImmutableList.of(THIRD, fourth)), eq("main"), anyList())).thenReturn(negative());
        CommitEnrichment checkedThird = stored(REPOSITORY_ID, THIRD, Optional.empty(), Optional.of(NOW));
        CommitEnrichment checkedFourth = stored(REPOSITORY_ID, fourth, Optional.empty(), Optional.of(NOW));
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(THIRD, fourth))).thenReturn(snapshot(checkedThird, checkedFourth));

        SelectedCommitPullRequestReader.Read read = reader.read(workspace(false), selected);

        assertThat(read.items()).extracting(SelectedCommitPullRequestReader.Item::lookupAttempted).containsExactly(false, false, true, true);
        assertThat(read.items()).extracting(SelectedCommitPullRequestReader.Item::checkedAt)
                .containsExactly(fresh.associationCheckedAt(), old.associationCheckedAt(), Optional.of(NOW), Optional.of(NOW));
        assertThat(read.items()).allSatisfy(item -> {
            assertThat(item.lookupFailed()).isFalse();
            assertThat(item.persisted()).isTrue();
        });
        assertThat(read.gaps()).singleElement().asString().contains("does not prove that no PR contains");
        verify(enrichment).upsertEnrichment(ImmutableList.of(checkedThird, checkedFourth));
    }

    @Test
    void routesForkAssociationsByStableUpstreamIdentityAndBoundDefaultBranch()
    {
        WorkspaceRepositoryContext workspace = workspace(true);
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST)))
                .thenReturn(snapshot(), snapshot(stored(REPOSITORY_ID, FIRST, Optional.of(9), Optional.of(NOW))));
        metadata(FIRST, NOW);
        when(lookup.lookUpResult(eq(MONITORED), eq(ImmutableList.of(FIRST)), eq("release"), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(association(FIRST, 9)), ImmutableSet.of()));

        assertThat(reader.read(workspace, ImmutableList.of(FIRST)).items().getFirst().pullRequestNumber()).contains(9);

        verify(enrichment, never()).findEnrichment(eq(101L), any());
        verify(lookup).lookUpResult(eq(MONITORED), eq(ImmutableList.of(FIRST)), eq("release"), anyList());
        verify(git).run(REPOSITORY, Duration.ofSeconds(2), arguments(FIRST));
    }

    @Test
    void realGitAcceptsExactCommitMetadataIgnoresItsBodyAndRejectsAnnotatedTagIdentity(@TempDir Path repository)
            throws IOException
    {
        GitCommandRunner localGit = new GitCommandRunner();
        command(localGit, repository, "init", "-q");
        Files.writeString(repository.resolve("example.txt"), "Fixture");
        command(localGit, repository, "add", "example.txt");
        command(localGit, repository, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Title (#321)\n\n" + "body".repeat(1000));
        String commit = command(localGit, repository, "rev-parse", "HEAD").strip();
        command(localGit, repository, "-c", "user.name=Test", "-c", "user.email=test@example.com", "tag", "-a", "v1", "-m", "Annotated tag");
        String tag = command(localGit, repository, "rev-parse", "refs/tags/v1").strip();
        assertThat(tag).isNotEqualTo(commit);
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(commit, tag))).thenReturn(snapshot());
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(commit)))
                .thenReturn(snapshot(stored(REPOSITORY_ID, commit, Optional.empty(), Optional.of(NOW))));
        when(lookup.lookUpResult(eq(MONITORED), eq(ImmutableList.of(commit)), eq("main"), anyList())).thenReturn(negative());
        WorkspaceRepositoryContext workspace = new WorkspaceRepositoryContext("workspace", repository, REPOSITORY_ID, MONITORED,
                OptionalLong.empty(), MONITORED, "origin", "origin", "main", ImmutableList.of());

        SelectedCommitPullRequestReader.Read read = new SelectedCommitPullRequestReader(enrichment, localGit, lookup, Clock.fixed(NOW, ZoneOffset.UTC))
                .read(workspace, ImmutableList.of(commit, tag));

        assertThat(read.items().getFirst().checkedAt()).contains(NOW);
        assertThat(read.items().getFirst().pullRequestNumber()).isEmpty();
        assertThat(read.items().getLast().lookupAttempted()).isFalse();
        assertThat(read.items().getLast().checkedAt()).isEmpty();
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains(tag, "exact, complete local commit"));
        verify(lookup).lookUpResult(eq(MONITORED), eq(ImmutableList.of(commit)), eq("main"), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "--all", "abc123", " aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "g", "duplicate", "empty", "too-many"})
    void rejectsInvalidSelectionsBeforeAnyIo(String kind)
    {
        List<String> selected = switch (kind) {
            case "duplicate" -> ImmutableList.of(FIRST, FIRST.toUpperCase());
            case "empty" -> ImmutableList.of();
            case "too-many" -> IntStream.range(0, 11).mapToObj(index -> "%040x".formatted(index)).collect(toImmutableList());
            default -> ImmutableList.of(kind);
        };

        assertThatThrownBy(() -> reader.read(workspace(false), selected)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(enrichment, git, lookup);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "truncated", "oversized", "peeled-tag", "extra-line", "exception"})
    void incompleteLocalMetadataPreservesPriorCheckWithoutCallingProvider(String kind)
    {
        Instant priorCheck = NOW.minus(Duration.ofDays(2));
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST)))
                .thenReturn(snapshot(stored(REPOSITORY_ID, FIRST, Optional.empty(), Optional.of(priorCheck))));
        GitCommandRunner.Result result = switch (kind) {
            case "missing", "exception" -> GitCommandRunner.Result.failed("Missing local object");
            case "truncated" -> new GitCommandRunner.Result(0, FIRST + "\n" + NOW + "\n", true);
            case "oversized" -> new GitCommandRunner.Result(0, FIRST + "\n" + "x".repeat(256) + "\n", false);
            case "peeled-tag" -> new GitCommandRunner.Result(0, SECOND + "\n" + NOW + "\n", false);
            case "extra-line" -> new GitCommandRunner.Result(0, FIRST + "\n" + NOW + "\nCommit title (#123)\n", false);
            default -> throw new AssertionError(kind);
        };
        when(git.run(REPOSITORY, Duration.ofSeconds(2), arguments(FIRST))).thenReturn(result);
        if (kind.equals("exception")) {
            when(git.run(REPOSITORY, Duration.ofSeconds(2), arguments(FIRST))).thenThrow(new IllegalStateException("Unavailable"));
        }

        SelectedCommitPullRequestReader.Read read = reader.read(workspace(false), ImmutableList.of(FIRST));

        assertThat(read.items().getFirst().checkedAt()).contains(priorCheck);
        assertThat(read.items().getFirst().lookupAttempted()).isFalse();
        assertThat(read.items().getFirst().lookupFailed()).isFalse();
        assertThat(read.items().getFirst().persisted()).isTrue();
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("no provider lookup was attempted"));
        verifyNoInteractions(lookup);
        verify(enrichment, never()).upsertEnrichment(any());
    }

    @Test
    void missingLocalCommitWithoutCachedObservationRemainsUnchecked()
    {
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST))).thenReturn(snapshot());
        when(git.run(REPOSITORY, Duration.ofSeconds(2), arguments(FIRST))).thenReturn(GitCommandRunner.Result.failed("Missing"));

        SelectedCommitPullRequestReader.Item item = reader.read(workspace(false), ImmutableList.of(FIRST)).items().getFirst();

        assertThat(item.checkedAt()).isEmpty();
        assertThat(item.pullRequestNumber()).isEmpty();
        assertThat(item.persisted()).isFalse();
        verifyNoInteractions(lookup);
        verify(enrichment, never()).upsertEnrichment(any());
    }

    @Test
    void batchesSuccessfulChecksWithoutSavingFailuresOrOverwritingAvatarFacts()
    {
        Instant previousCheck = NOW.minus(Duration.ofDays(2));
        List<String> selected = ImmutableList.of(FIRST, SECOND, THIRD);
        CommitEnrichment previous = stored(REPOSITORY_ID, THIRD, Optional.empty(), Optional.of(previousCheck));
        when(enrichment.findEnrichment(REPOSITORY_ID, selected)).thenReturn(snapshot(previous));
        selected.forEach(sha -> metadata(sha, NOW));
        when(lookup.lookUpResult(eq(MONITORED), eq(selected), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(association(FIRST, 9)), ImmutableSet.of(THIRD)));
        CommitEnrichment positive = stored(REPOSITORY_ID, FIRST, Optional.of(9), Optional.of(NOW));
        CommitEnrichment negative = stored(REPOSITORY_ID, SECOND, Optional.empty(), Optional.of(NOW));
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST, SECOND))).thenReturn(snapshot(positive, negative));

        SelectedCommitPullRequestReader.Read read = reader.read(workspace(false), selected);

        assertThat(read.items()).extracting(SelectedCommitPullRequestReader.Item::sha).containsExactlyElementsOf(selected);
        assertThat(read.items().getFirst().pullRequestNumber()).contains(9);
        assertThat(read.items().get(1).checkedAt()).contains(NOW);
        assertThat(read.items().getLast().checkedAt()).contains(previousCheck);
        assertThat(read.items().getLast().lookupFailed()).isTrue();
        assertThat(read.items()).allSatisfy(item -> assertThat(item.lookupAttempted()).isTrue());
        ArgumentCaptor<Collection<CommitEnrichment>> saved = ArgumentCaptor.captor();
        verify(enrichment).upsertEnrichment(saved.capture());
        assertThat(saved.getValue().iterator().next().pullRequestMergedAt()).contains(NOW);
        assertThat(saved.getValue()).containsExactly(positive, negative).allSatisfy(value -> {
            assertThat(value.authorLogin()).isEmpty();
            assertThat(value.avatarKey()).isEmpty();
            assertThat(value.committerLogin()).isEmpty();
            assertThat(value.committerAvatarKey()).isEmpty();
            assertThat(value.avatarCheckedAt()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-association", "duplicate", "overlap", "foreign-failure", "exception"})
    void inconsistentProviderResultsNeverBecomeSavedNegatives(String kind)
    {
        Instant priorCheck = NOW.minus(Duration.ofDays(2));
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST)))
                .thenReturn(snapshot(stored(REPOSITORY_ID, FIRST, Optional.empty(), Optional.of(priorCheck))));
        metadata(FIRST, NOW);
        PullRequestLookupResult result = switch (kind) {
            case "foreign-association" -> new PullRequestLookupResult(ImmutableList.of(association(SECOND, 9)), ImmutableSet.of());
            case "duplicate" -> new PullRequestLookupResult(ImmutableList.of(association(FIRST, 9), association(FIRST, 9)), ImmutableSet.of());
            case "overlap" -> new PullRequestLookupResult(ImmutableList.of(association(FIRST, 9)), ImmutableSet.of(FIRST));
            case "foreign-failure" -> new PullRequestLookupResult(ImmutableList.of(), ImmutableSet.of(SECOND));
            case "exception" -> negative();
            default -> throw new AssertionError(kind);
        };
        when(lookup.lookUpResult(eq(MONITORED), eq(ImmutableList.of(FIRST)), eq("main"), anyList())).thenReturn(result);
        if (kind.equals("exception")) {
            when(lookup.lookUpResult(eq(MONITORED), eq(ImmutableList.of(FIRST)), eq("main"), anyList()))
                    .thenThrow(new IllegalStateException("Provider unavailable"));
        }

        SelectedCommitPullRequestReader.Read read = reader.read(workspace(false), ImmutableList.of(FIRST));

        assertThat(read.items().getFirst().checkedAt()).contains(priorCheck);
        assertThat(read.items().getFirst().lookupAttempted()).isTrue();
        assertThat(read.items().getFirst().lookupFailed()).isTrue();
        assertThat(read.items().getFirst().persisted()).isTrue();
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("no new negatives were saved"));
        verify(enrichment, never()).upsertEnrichment(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "reread", "missing-row", "old-row"})
    void unconfirmedPersistenceRetainsObservedResultWithExplicitUnpersistedFlag(String failure)
    {
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST))).thenReturn(snapshot());
        metadata(FIRST, NOW);
        when(lookup.lookUpResult(eq(MONITORED), eq(ImmutableList.of(FIRST)), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(association(FIRST, 9)), ImmutableSet.of()));
        if (failure.equals("save")) {
            doThrow(new IllegalStateException("Disk unavailable")).when(enrichment).upsertEnrichment(any());
        }
        else if (failure.equals("reread")) {
            when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST))).thenReturn(snapshot()).thenThrow(new IllegalStateException("Cannot confirm"));
        }
        else if (failure.equals("old-row")) {
            when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST)))
                    .thenReturn(snapshot(), snapshot(stored(REPOSITORY_ID, FIRST, Optional.empty(), Optional.of(NOW.minusSeconds(1)))));
        }

        SelectedCommitPullRequestReader.Read read = reader.read(workspace(false), ImmutableList.of(FIRST));

        assertThat(read.items().getFirst().pullRequestNumber()).contains(9);
        assertThat(read.items().getFirst().checkedAt()).contains(NOW);
        assertThat(read.items().getFirst().lookupAttempted()).isTrue();
        assertThat(read.items().getFirst().lookupFailed()).isFalse();
        assertThat(read.items().getFirst().persisted()).isFalse();
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("marked unpersisted"));
    }

    @Test
    void rereadsConcurrentPositiveRetainedByStorageMergeInsteadOfReturningObservedNegative()
    {
        CommitEnrichment concurrent = stored(REPOSITORY_ID, FIRST, Optional.of(27), Optional.of(NOW));
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST))).thenReturn(snapshot(), snapshot(concurrent));
        metadata(FIRST, NOW);
        when(lookup.lookUpResult(eq(MONITORED), eq(ImmutableList.of(FIRST)), eq("main"), anyList())).thenReturn(negative());

        SelectedCommitPullRequestReader.Read read = reader.read(workspace(false), ImmutableList.of(FIRST));

        assertThat(read.items().getFirst().pullRequestNumber()).contains(27);
        assertThat(read.items().getFirst().persisted()).isTrue();
        assertThat(read.gaps()).isEmpty();
        verify(enrichment).upsertEnrichment(ImmutableList.of(stored(REPOSITORY_ID, FIRST, Optional.empty(), Optional.of(NOW))));
    }

    @Test
    void avatarOnlyRowDoesNotClaimAPersistedAssociationAfterLocalReadFailure()
    {
        CommitEnrichment avatars = new CommitEnrichment(REPOSITORY_ID, FIRST, Optional.of("author"), Optional.of("a".repeat(64)),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(NOW), Optional.empty(), NOW);
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST))).thenReturn(snapshot(avatars));
        when(git.run(REPOSITORY, Duration.ofSeconds(2), arguments(FIRST))).thenReturn(GitCommandRunner.Result.failed("Missing"));

        SelectedCommitPullRequestReader.Item item = reader.read(workspace(false), ImmutableList.of(FIRST)).items().getFirst();

        assertThat(item.persisted()).isFalse();
        assertThat(item.checkedAt()).isEmpty();
        verifyNoInteractions(lookup);
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository", "commit", "duplicate"})
    void rejectsMisboundStoredAssociationsBeforeGitOrProviderRead(String mismatch)
    {
        CommitEnrichment value = stored(mismatch.equals("repository") ? 99 : REPOSITORY_ID, mismatch.equals("commit") ? SECOND : FIRST, Optional.of(9), Optional.of(NOW));
        when(enrichment.findEnrichment(REPOSITORY_ID, ImmutableList.of(FIRST)))
                .thenReturn(mismatch.equals("duplicate") ? snapshot(value, value) : snapshot(value));

        assertThatThrownBy(() -> reader.read(workspace(false), ImmutableList.of(FIRST))).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(git, lookup);
        verify(enrichment, never()).upsertEnrichment(any());
    }

    private void metadata(String sha, Instant committedAt)
    {
        when(git.run(REPOSITORY, Duration.ofSeconds(2), arguments(sha)))
                .thenReturn(new GitCommandRunner.Result(0, sha + "\n" + committedAt + "\n", false));
    }

    private static String command(GitCommandRunner runner, Path repository, String... arguments)
    {
        GitCommandRunner.Result result = runner.run(repository, arguments);
        assertThat(result.successful()).as("git %s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output();
    }

    private static List<String> arguments(String sha)
    {
        return ImmutableList.of("log", "--no-show-signature", "--format=%H%n%cI", "-1", sha, "--");
    }

    private static PullRequestLookupResult negative()
    {
        return new PullRequestLookupResult(ImmutableList.of(), ImmutableSet.of());
    }

    private static PullRequestLookupResult.Association association(String sha, int number)
    {
        return new PullRequestLookupResult.Association(sha,
                new PullRequestAssociation(number, "Pull request " + number, "https://github.com/owner/project/pull/" + number, "main", NOW.toString()));
    }

    private static CommitEnrichment stored(long repositoryId, String sha, Optional<Integer> number, Optional<Instant> checkedAt)
    {
        return new CommitEnrichment(repositoryId, sha, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), number,
                number.map(value -> "Pull request " + value), number.map(value -> "https://github.com/owner/project/pull/" + value), number.map(value -> NOW),
                Optional.empty(), checkedAt, NOW);
    }

    private static CommitEnrichmentSnapshot snapshot(CommitEnrichment... values)
    {
        return new CommitEnrichmentSnapshot(ImmutableList.copyOf(values));
    }

    private static WorkspaceRepositoryContext workspace(boolean fork)
    {
        return new WorkspaceRepositoryContext("workspace", REPOSITORY, fork ? 101 : REPOSITORY_ID,
                fork ? RepoRef.of("contributor", "project") : MONITORED, fork ? OptionalLong.of(REPOSITORY_ID) : OptionalLong.empty(), MONITORED,
                fork ? "upstream" : "origin", "origin", fork ? "release" : "main", ImmutableList.of());
    }
}
