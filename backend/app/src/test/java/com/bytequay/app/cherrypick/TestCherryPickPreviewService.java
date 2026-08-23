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
package com.bytequay.app.cherrypick;

import com.bytequay.app.cherrypick.CherryPickCommitPreview.TargetMatch;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote.Role;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.store.CommitHistoryStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCherryPickPreviewService
{
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path temporaryDirectory;

    private Path upstream;
    private Path fork;
    private Path clone;
    private MutableClock clock;
    private CommitHistoryStore commitHistory;
    private CredentialService credentials;
    private CherryPickPreviewService service;

    @BeforeEach
    void setUp()
            throws IOException
    {
        upstream = init("upstream");
        fork = init("fork");
        write(upstream, "seed.txt", "seed\n");
        commit(upstream, "Seed");
        git(upstream, "branch", "-M", "main");
        git(fork, "remote", "add", "upstream", upstream.toString());
        git(fork, "fetch", "--quiet", "upstream");
        git(fork, "checkout", "--quiet", "-B", "main", "upstream/main");

        clone = init("clone");
        git(clone, "remote", "add", "origin", upstream.toString());
        git(clone, "remote", "add", "fork", fork.toString());
        clock = new MutableClock(NOW);
        commitHistory = mock(CommitHistoryStore.class);
        credentials = mock(CredentialService.class);
        when(credentials.gitAuthenticationEnvironment(anyString(), anyCollection()))
                .thenReturn(ImmutableMap.of());
        WorkspaceRemoteSourceService remoteSources = mock(WorkspaceRemoteSourceService.class);
        when(remoteSources.list("workspace-id")).thenReturn(ImmutableList.of(
                new WorkspaceRemote("origin", upstream.toString(), Role.ORIGIN),
                new WorkspaceRemote("fork", fork.toString(), Role.CUSTOM)));
        service = new CherryPickPreviewService(
                GIT,
                new CherryPickConflictScout(GIT),
                commitHistory,
                credentials,
                remoteSources,
                clock);
    }

    @Test
    void readsStoredPullRequestAssociationsWithoutProviderAccess()
    {
        String base = head(upstream);
        write(upstream, "associated.txt", "associated\n");
        commit(upstream, "Associated change");
        String sha = head(upstream);
        when(commitHistory.findEnrichment(202, List.of(sha))).thenReturn(new CommitEnrichmentSnapshot(List.of(
                new CommitEnrichment(
                        202,
                        sha,
                        Optional.empty(),
                        Optional.of(42),
                        Optional.of("Example pull request"),
                        Optional.of("https://example.invalid/pull/42"),
                        Optional.empty(),
                        Optional.of(NOW),
                        NOW))));

        CherryPickPreview preview = service.preview(new PreviewCherryPickRangeCommand(
                clone,
                Optional.of("workspace-id"),
                OptionalLong.of(202),
                "origin",
                base,
                "origin/main",
                "fork",
                "main"));

        assertThat(preview.commits()).singleElement().satisfies(value -> {
            assertThat(value.pullRequest()).isPresent();
            assertThat(value.pullRequest().orElseThrow().number()).isEqualTo(42);
            assertThat(value.pullRequest().orElseThrow().title()).isEqualTo("Example pull request");
        });
        verify(credentials).gitAuthenticationEnvironment(
                "workspace-id", ImmutableList.of(upstream.toString(), fork.toString()));
    }

    @Test
    void rejectsRemoteRoutingDriftBeforeAddingTheWorkspaceCredential()
    {
        git(clone, "remote", "set-url", "origin", temporaryDirectory.resolve("unexpected.git").toString());

        assertThatThrownBy(() -> service.preview(new PreviewCherryPickRangeCommand(
                clone,
                Optional.of("workspace-id"),
                OptionalLong.empty(),
                "origin",
                "origin/main",
                "origin/main",
                "fork",
                "main")))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("does not match persisted workspace routing");
        verifyNoInteractions(credentials);
    }

    @Test
    void ordersTheRangeAndLeavesEveryWorktreeUntouched()
    {
        String base = head(upstream);
        for (int index = 1; index <= 12; index++) {
            write(upstream, "file" + index + ".txt", "content " + index + "\n");
            commit(upstream, "Upstream change " + index);
        }
        String forkHead = head(fork);
        String upstreamHead = head(upstream);

        CherryPickPreview preview = service.preview(command(base, "main"));

        assertThat(preview.commits()).hasSize(12);
        assertThat(preview.commits().stream().map(CherryPickCommitPreview::position))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 12).boxed().collect(toImmutableList()));
        assertThat(preview.commits().stream().map(CherryPickCommitPreview::title))
                .containsExactly(IntStream.rangeClosed(1, 12)
                        .mapToObj(index -> "Upstream change " + index)
                        .toArray(String[]::new));
        assertThat(preview.sourceBaseSha()).isEqualTo(base);
        assertThat(preview.sourceTipSha()).isEqualTo(upstreamHead);
        assertThat(preview.localTargetBranch()).isEqualTo("fork-main");
        assertThat(preview.commits()).noneMatch(CherryPickCommitPreview::alreadyOnTarget);
        assertThat(preview.commits().getFirst().changedPaths()).containsExactly("file1.txt");

        // Preview reads; it never moves a branch or dirties a worktree.
        assertThat(head(fork)).isEqualTo(forkHead);
        assertThat(head(upstream)).isEqualTo(upstreamHead);
        assertThat(git(clone, "status", "--porcelain")).isEmpty();
        assertThat(git(fork, "status", "--porcelain")).isEmpty();

        assertThat(service.preview(command(base, "main")).commits().stream()
                .map(CherryPickCommitPreview::sourceSha))
                .containsExactlyElementsOf(preview.commits().stream()
                        .map(CherryPickCommitPreview::sourceSha)
                        .collect(toImmutableList()));
    }

    @Test
    void marksDirectAncestorsAndExactOriginLinesOnly()
    {
        String base = head(upstream);
        write(upstream, "shared.txt", "shared\n");
        commit(upstream, "Shared change");
        String shared = head(upstream);
        write(upstream, "picked.txt", "picked\n");
        commit(upstream, "Picked change");
        String picked = head(upstream);
        write(upstream, "abbreviated.txt", "abbreviated\n");
        commit(upstream, "Abbreviated marker");
        String abbreviated = head(upstream);
        write(upstream, "prose.txt", "prose\n");
        commit(upstream, "Prose marker");
        String prose = head(upstream);
        write(upstream, "duplicate.txt", "duplicate\n");
        commit(upstream, "Shared change");
        String duplicateTitle = head(upstream);

        // The fork merges one upstream commit outright, and cherry-picks another.
        git(fork, "fetch", "--quiet", "upstream");
        git(fork, "merge", "--quiet", "--ff-only", shared);
        git(fork, "cherry-pick", "--quiet", "-x", picked);
        String pickedOnFork = head(fork);
        write(fork, "note.txt", "note\n");
        commitWithBody(
                fork,
                "Mentions markers without recording one",
                "(cherry picked from commit " + abbreviated.substring(0, 12) + ")\n"
                        + "see (cherry picked from commit " + prose + ") in the discussion\n");

        CherryPickPreview preview = service.preview(command(base, "main"));

        assertThat(match(preview, shared).orElseThrow().kind()).isEqualTo(TargetMatch.Kind.DIRECT_ANCESTOR);
        assertThat(match(preview, picked).orElseThrow().kind()).isEqualTo(TargetMatch.Kind.CHERRY_PICK_ORIGIN);
        assertThat(match(preview, picked).orElseThrow().targetCommitSha()).isEqualTo(pickedOnFork);
        assertThat(match(preview, abbreviated)).as("abbreviated marker is not evidence").isEmpty();
        assertThat(match(preview, prose)).as("marker inside prose is not evidence").isEmpty();
        assertThat(match(preview, duplicateTitle)).as("duplicate title is not evidence").isEmpty();
    }

    @Test
    void reportsPatchEquivalenceAsAdvisoryOnly()
    {
        String base = head(upstream);
        write(upstream, "same.txt", "same content\n");
        commit(upstream, "Same content upstream");
        String equivalent = head(upstream);

        // The fork applies the identical patch under a different commit, so no
        // ancestry and no origin line exist.
        write(fork, "same.txt", "same content\n");
        commit(fork, "Same content on the fork");

        CherryPickPreview preview = service.preview(command(base, "main"));

        assertThat(match(preview, equivalent)).isEmpty();
        assertThat(preview.commits().getFirst().patchEquivalent()).isTrue();
        assertThat(preview.warnings()).anyMatch(warning -> warning.contains("advisory"));
    }

    @Test
    void flagsOriginallyEmptyCommitsForDisplay()
    {
        String base = head(upstream);
        git(upstream, "commit", "--quiet", "--allow-empty", "-m", "Empty upstream commit");
        write(upstream, "later.txt", "later\n");
        commit(upstream, "Later change");

        CherryPickPreview preview = service.preview(command(base, "main"));

        assertThat(preview.commits().getFirst().originallyEmpty()).isTrue();
        assertThat(preview.commits().getFirst().changedPaths()).isEmpty();
        assertThat(preview.commits().getFirst().alreadyOnTarget())
                .as("an empty commit is still planned so execution records EMPTY_SKIPPED")
                .isFalse();
        assertThat(preview.commits().getLast().originallyEmpty()).isFalse();
    }

    @Test
    void rejectsMergeCommitsWithTheOffendingSha()
    {
        String base = head(upstream);
        git(upstream, "checkout", "--quiet", "-b", "side");
        write(upstream, "side.txt", "side\n");
        commit(upstream, "Side change");
        git(upstream, "checkout", "--quiet", "main");
        write(upstream, "main.txt", "main\n");
        commit(upstream, "Main change");
        git(upstream, "merge", "--quiet", "--no-ff", "-m", "Merge side", "side");
        String merge = head(upstream);

        assertThatThrownBy(() -> service.preview(command(base, "main")))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("merge commit")
                .hasMessageContaining(merge.substring(0, 12));
    }

    @Test
    void rejectsUnresolvableRefsAndNonAncestorRanges()
    {
        String base = head(upstream);
        write(upstream, "change.txt", "change\n");
        commit(upstream, "Upstream change");

        assertThatThrownBy(() -> service.preview(command("refs/heads/missing", "main")))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("Cannot resolve ref");

        git(upstream, "checkout", "--quiet", "-b", "unrelated", base);
        write(upstream, "unrelated.txt", "unrelated\n");
        commit(upstream, "Unrelated change");
        String unrelated = head(upstream);
        git(upstream, "checkout", "--quiet", "main");

        assertThatThrownBy(() -> service.preview(command(unrelated, "main")))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("is not an ancestor of tip");
    }

    @Test
    void refusesADivergedLocalTargetBranchInsteadOfForcingIt()
    {
        String base = head(upstream);
        write(upstream, "change.txt", "change\n");
        commit(upstream, "Upstream change");
        service.preview(command(base, "main"));

        // A local commit on the synchronized target branch is user work.
        git(clone, "checkout", "--quiet", "fork-main");
        write(clone, "local.txt", "local\n");
        commit(clone, "Local-only commit");
        git(clone, "checkout", "--quiet", "--detach");
        write(fork, "fork.txt", "fork\n");
        commit(fork, "Fork moved on");

        assertThatThrownBy(() -> service.preview(command(base, "main")))
                .isInstanceOf(CherryPickConflictException.class)
                .hasMessageContaining("diverged");
    }

    @Test
    void refusesToSynchronizeACheckedOutTargetBranch()
    {
        String base = head(upstream);
        write(upstream, "change.txt", "change\n");
        commit(upstream, "Upstream change");
        service.preview(command(base, "main"));
        git(clone, "checkout", "--quiet", "fork-main");

        assertThatThrownBy(() -> service.preview(command(base, "main")))
                .isInstanceOf(CherryPickConflictException.class)
                .hasMessageContaining("checked out");
    }

    @Test
    void expiresPreviewsAndCapsTheCache()
    {
        String base = head(upstream);
        write(upstream, "change.txt", "change\n");
        commit(upstream, "Upstream change");

        CherryPickPreview first = service.preview(command(base, "main"));
        assertThat(service.find(first.previewId())).isPresent();
        assertThat(service.find("unknown")).isEmpty();

        for (int index = 0; index < CherryPickPreviewService.MAX_CACHED_PREVIEWS; index++) {
            service.preview(command(base, "main"));
        }
        assertThat(service.find(first.previewId())).as("oldest preview is evicted").isEmpty();

        CherryPickPreview live = service.preview(command(base, "main"));
        clock.advance(CherryPickPreviewService.PREVIEW_LIFETIME.plusSeconds(1));
        assertThat(service.find(live.previewId())).as("expired preview is gone").isEmpty();
    }

    @Test
    void rejectsControlCharactersAndOptionLikeRefs()
    {
        assertThatThrownBy(() -> new PreviewCherryPickRangeCommand(
                clone, Optional.empty(), OptionalLong.empty(), "origin", "main\nrm -rf /", "main", "fork", "main"))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("control character");

        assertThatThrownBy(() -> new PreviewCherryPickRangeCommand(
                clone, Optional.empty(), OptionalLong.empty(), "origin", "--upload-pack=evil", "main", "fork", "main"))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("command-line option");

        assertThatThrownBy(() -> new PreviewCherryPickRangeCommand(
                Path.of("relative"), Optional.empty(), OptionalLong.empty(), "origin", "main", "main", "fork", "main"))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("not absolute");
    }

    private PreviewCherryPickRangeCommand command(String baseRef, String targetBranch)
    {
        return new PreviewCherryPickRangeCommand(
                clone, Optional.empty(), OptionalLong.empty(), "origin", baseRef, "origin/main", "fork", targetBranch);
    }

    private static Optional<TargetMatch> match(CherryPickPreview preview, String sourceSha)
    {
        return preview.commits().stream()
                .filter(commit -> commit.sourceSha().equals(sourceSha))
                .findFirst()
                .orElseThrow(() -> new AssertionError("commit not in preview: " + sourceSha))
                .targetMatch();
    }

    private Path init(String name)
            throws IOException
    {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        git(root.getParent(), "init", "--quiet", "-b", "main", root.toString());
        git(root, "config", "user.name", "ByteQuay Test");
        git(root, "config", "user.email", "bytequay@example.com");
        return root.toRealPath();
    }

    private static void write(Path repository, String name, String content)
    {
        try {
            Files.writeString(repository.resolve(name), content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void commit(Path repository, String subject)
    {
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", subject);
    }

    private static void commitWithBody(Path repository, String subject, String body)
    {
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", subject, "-m", body);
    }

    private static String head(Path repository)
    {
        return git(repository, "rev-parse", "HEAD");
    }

    private static String git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.exitCode())
                .as("git %s: %s", String.join(" ", arguments), result.output())
                .isZero();
        return result.output().strip();
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant instant;

        private MutableClock(Instant instant)
        {
            this.instant = instant;
        }

        private void advance(Duration amount)
        {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant()
        {
            return instant;
        }

        @Override
        public java.time.ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone)
        {
            return this;
        }
    }
}
