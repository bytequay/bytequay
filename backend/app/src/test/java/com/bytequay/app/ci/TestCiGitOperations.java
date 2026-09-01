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
package com.bytequay.app.ci;

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.PushJournal;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.bytequay.app.ci.CiGitInspection.Outcome.INVALID;
import static com.bytequay.app.ci.CiGitInspection.Outcome.NO_CHANGE;
import static com.bytequay.app.ci.CiGitInspection.Outcome.READY;
import static com.bytequay.app.ci.CiPushResult.Outcome.PUSHED;
import static com.bytequay.app.ci.CiPushResult.Outcome.REJECTED;
import static com.bytequay.app.ci.CiPushResult.Outcome.RETRYABLE;
import static com.bytequay.app.ci.CiPushResult.Outcome.STALE;
import static com.bytequay.app.ci.FixupPositionCheck.Result.VALID;
import static org.assertj.core.api.Assertions.assertThat;

class TestCiGitOperations
{
    private static final Instant NOW = Instant.parse("2026-08-18T04:00:00Z");
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("acme", "project");
    private static final String HEAD_REF = "feature";
    private static final String REMOTE_URL = "https://github.com/acme/project.git";

    @TempDir
    Path temporaryDirectory;

    @Test
    void synchronizesTheExactFetchedBaseAndRemovesThePrivateRef()
            throws IOException
    {
        Repository fixture = repository("base-sync");
        SynchronizeCiBaseCommand existingCommand = synchronize(fixture, fixture.baseSha());
        assertThat(service().synchronizeBase(existingCommand).outcome())
                .isEqualTo(CiBaseSynchronization.Outcome.READY);
        assertThat(git(fixture.worktree(), "rev-parse", "refs/heads/" + existingCommand.localBaseBranch()))
                .isEqualTo(fixture.baseSha());

        String nextBase = advanceRemoteBase(fixture);
        gitFailure(fixture.worktree(), "cat-file", "-e", nextBase + "^{commit}");
        SynchronizeCiBaseCommand command = synchronize(fixture, nextBase);

        CiBaseSynchronization synchronizedBase = service().synchronizeBase(command);

        assertThat(synchronizedBase.outcome()).isEqualTo(CiBaseSynchronization.Outcome.READY);
        assertThat(synchronizedBase.localBaseBranch()).contains(command.localBaseBranch());
        assertThat(git(fixture.worktree(), "rev-parse", "refs/heads/" + command.localBaseBranch()))
                .isEqualTo(nextBase);
        assertThat(service().removeSynchronizedBase(command)).isTrue();
        assertThat(service().removeSynchronizedBase(command)).isTrue();
        gitFailure(fixture.worktree(), "rev-parse", "--verify", "refs/heads/" + command.localBaseBranch());
    }

    @Test
    void rejectsAnUnavailableExactBase()
            throws IOException
    {
        Repository fixture = repository("missing-base");
        SynchronizeCiBaseCommand command = synchronize(fixture, "f".repeat(40));

        CiBaseSynchronization result = service().synchronizeBase(command);

        assertThat(result.outcome()).isEqualTo(CiBaseSynchronization.Outcome.INVALID);
        assertThat(result.localBaseBranch()).isEmpty();
        assertThat(result.diagnostics()).contains("exact pull request base SHA is unavailable");
        gitFailure(fixture.worktree(), "rev-parse", "--verify", "refs/heads/" + command.localBaseBranch());
    }

    @Test
    void inspectsNoChangeAndOneCommittedFixup()
            throws IOException
    {
        Repository fixture = repository("inspection");
        CiGitOperations service = service();

        CiGitInspection unchanged = service.inspectAgentResult(inspect(fixture));
        assertThat(unchanged.outcome()).isEqualTo(NO_CHANGE);

        write(fixture.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(fixture.worktree(), "fixup! Target change");
        CiGitInspection ready = service.inspectAgentResult(inspect(fixture));
        CiGitInspection repeated = service.inspectAgentResult(inspect(fixture));

        assertThat(ready.outcome()).isEqualTo(READY);
        assertThat(ready.changedPaths()).containsExactly("source.txt");
        assertThat(ready.localHead()).contains(head(fixture.worktree()));
        assertThat(repeated.fingerprint()).isEqualTo(ready.fingerprint());
    }

    @Test
    void rejectsDirtyStagedAndUntrackedFiles()
            throws IOException
    {
        Repository fixture = repository("dirty");
        CiGitOperations service = service();

        write(fixture.worktree().resolve("source.txt"), "dirty\n");
        assertInvalid(service, fixture, "not clean");

        git(fixture.worktree(), "add", "source.txt");
        assertInvalid(service, fixture, "not clean");

        git(fixture.worktree(), "reset", "--hard", fixture.inputSha());
        write(fixture.worktree().resolve("untracked.txt"), "untracked\n");
        assertInvalid(service, fixture, "not clean");
    }

    @Test
    void rejectsUnresolvedConflictAndCommittedConflictMarker()
            throws IOException
    {
        Repository conflict = repository("conflict");
        git(conflict.worktree(), "switch", "-c", "other", conflict.inputSha());
        write(conflict.worktree().resolve("source.txt"), "other\n");
        commit(conflict.worktree(), "Other change");
        git(conflict.worktree(), "switch", HEAD_REF);
        write(conflict.worktree().resolve("source.txt"), "feature\n");
        commit(conflict.worktree(), "Feature change");
        gitFailure(conflict.worktree(), "merge", "other");
        assertInvalid(service(), conflict, "unresolved index entries");

        Repository markers = repository("markers");
        write(markers.worktree().resolve("source.txt"), "<<<<<<< ours\nleft\n=======\nright\n>>>>>>> theirs\n");
        commit(markers.worktree(), "fixup! Target change");
        assertInvalid(service(), markers, "Git rejected the committed diff");
    }

    @Test
    void rejectsOpenMergeCherryPickAndRebaseState()
            throws IOException
    {
        for (String state : ImmutableList.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "rebase-merge")) {
            Repository fixture = repository("open-" + state.toLowerCase().replace('_', '-'));
            Path gitState = gitPath(fixture.worktree(), state);
            if (state.equals("rebase-merge")) {
                Files.createDirectories(gitState);
            }
            else {
                write(gitState, fixture.inputSha() + "\n");
            }
            assertInvalid(service(), fixture, "still open");
        }
    }

    @Test
    void rejectsDetachedAndWrongBranch()
            throws IOException
    {
        Repository detached = repository("detached");
        git(detached.worktree(), "switch", "--detach", detached.inputSha());
        assertInvalid(service(), detached, "detached or on another branch");

        Repository wrong = repository("wrong");
        git(wrong.worktree(), "switch", "-c", "other");
        assertInvalid(service(), wrong, "detached or on another branch");
    }

    @Test
    void rejectsMergeCommitAndCommitOutsideRange()
            throws IOException
    {
        Repository merged = repository("merge");
        git(merged.worktree(), "switch", "-c", "other", merged.inputSha());
        write(merged.worktree().resolve("other.txt"), "other\n");
        commit(merged.worktree(), "Other change");
        git(merged.worktree(), "switch", HEAD_REF);
        write(merged.worktree().resolve("feature.txt"), "feature\n");
        commit(merged.worktree(), "Feature change");
        git(merged.worktree(), "merge", "--no-ff", "other", "-m", "Merge other");
        assertInvalid(service(), merged, "not linear");

        Repository outside = repository("outside");
        git(outside.worktree(), "switch", "--orphan", "unrelated");
        write(outside.worktree().resolve("unrelated.txt"), "unrelated\n");
        commit(outside.worktree(), "Unrelated root");
        git(outside.worktree(), "branch", "-D", HEAD_REF);
        git(outside.worktree(), "branch", "-m", HEAD_REF);
        assertInvalid(service(), outside, "outside the authorized PR range");
    }

    @Test
    void pushesDescendantAndReconcilesAlreadyPushedHead()
            throws IOException
    {
        Repository fixture = repository("normal-push");
        write(fixture.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(fixture.worktree(), "fixup! Target change");
        CiGitInspection inspection = service().inspectAgentResult(inspect(fixture));
        PushCiHeadCommand command = push(fixture, inspection, 0, authorization(true));

        CiPushResult pushed = service().push(command);
        write(fixture.worktree().resolve("later.txt"), "later local work\n");
        commit(fixture.worktree(), "Later local change");
        Authorization expired = new Authorization(
                true,
                true,
                true,
                3,
                NOW.minusSeconds(120),
                NOW.minusSeconds(60));
        CiPushResult reconciled = service().push(new PushCiHeadCommand(
                fixture.worktree(),
                REPOSITORY,
                HEAD_REF,
                "origin",
                REPOSITORY,
                HEAD_REF,
                expired,
                command.journal()));

        assertThat(pushed.outcome()).isEqualTo(PUSHED);
        assertThat(reconciled.outcome()).isEqualTo(PUSHED);
        assertThat(remoteHead(fixture)).isEqualTo(inspection.localHead().orElseThrow());
    }

    @Test
    void pushesRewriteOnlyWithExactForceLease()
            throws IOException
    {
        Repository fixture = repository("rewrite");
        git(fixture.worktree(), "reset", "--hard", fixture.baseSha());
        write(fixture.worktree().resolve("source.txt"), "base\nrewritten target\n");
        commit(fixture.worktree(), "Target change");
        write(fixture.worktree().resolve("source.txt"), "base\nrewritten target\nfix\n");
        commit(fixture.worktree(), "fixup! Target change");
        CiGitInspection inspection = service().inspectAgentResult(inspect(fixture));

        CiPushResult pushed = service().push(push(fixture, inspection, 0, authorization(true)));

        assertThat(pushed.outcome()).isEqualTo(PUSHED);
        assertThat(remoteHead(fixture)).isEqualTo(inspection.localHead().orElseThrow());
        assertThat(CiGitOperations.pushArguments("origin", HEAD_REF, fixture.inputSha(), false))
                .containsExactly(
                        "push",
                        "--porcelain",
                        "--force-with-lease=refs/heads/feature:" + fixture.inputSha(),
                        "origin",
                        "HEAD:refs/heads/feature")
                .doesNotContain("--force");
        assertThat(CiGitOperations.pushArguments("origin", HEAD_REF, fixture.inputSha(), true))
                .containsExactly("push", "--porcelain", "origin", "HEAD:refs/heads/feature");
    }

    @Test
    void rejectsUnauthorizedRewriteAndStaleLease()
            throws IOException
    {
        Repository rewrite = repository("rewrite-rejected");
        git(rewrite.worktree(), "reset", "--hard", rewrite.baseSha());
        write(rewrite.worktree().resolve("source.txt"), "rewritten\n");
        commit(rewrite.worktree(), "Target change");
        CiGitInspection rewritten = service().inspectAgentResult(inspect(rewrite));
        assertThat(service().push(push(rewrite, rewritten, 0, authorization(false))).outcome())
                .isEqualTo(REJECTED);

        Repository stale = repository("stale");
        write(stale.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(stale.worktree(), "fixup! Target change");
        CiGitInspection local = service().inspectAgentResult(inspect(stale));
        git(stale.worktree(), "switch", "-c", "remote-advance", stale.inputSha());
        write(stale.worktree().resolve("remote.txt"), "remote\n");
        commit(stale.worktree(), "Remote advance");
        git(stale.worktree(), "push", stale.remote().toString(), "HEAD:refs/heads/" + HEAD_REF);
        git(stale.worktree(), "switch", HEAD_REF);

        assertThat(service().push(push(stale, local, 0, authorization(true))).outcome())
                .isEqualTo(STALE);
    }

    @Test
    void permitsOneIdenticalRetryWhenRemoteIsUnchanged()
            throws IOException
    {
        Repository fixture = repository("retry");
        write(fixture.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(fixture.worktree(), "fixup! Target change");
        CiGitInspection inspection = service().inspectAgentResult(inspect(fixture));
        Path hook = gitPath(fixture.worktree(), "hooks/pre-push");
        write(hook, "#!/bin/sh\nexit 1\n");
        assertThat(hook.toFile().setExecutable(true)).isTrue();

        assertThat(service().push(push(fixture, inspection, 0, authorization(true))).outcome())
                .isEqualTo(RETRYABLE);
        assertThat(service().push(push(fixture, inspection, 1, authorization(true))).outcome())
                .isEqualTo(REJECTED);
        assertThat(remoteHead(fixture)).isEqualTo(fixture.inputSha());
    }

    @Test
    void rechecksConsentExpiryAndFingerprintBeforePush()
            throws IOException
    {
        Repository fixture = repository("push-guards");
        write(fixture.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(fixture.worktree(), "fixup! Target change");
        CiGitInspection inspection = service().inspectAgentResult(inspect(fixture));

        Authorization noPush = new Authorization(
                true,
                true,
                false,
                3,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600));
        assertThat(service().push(push(fixture, inspection, 0, noPush)).outcome()).isEqualTo(REJECTED);

        Authorization expired = new Authorization(
                true,
                true,
                true,
                3,
                NOW.minusSeconds(120),
                NOW.minusSeconds(60));
        assertThat(service().push(push(fixture, inspection, 0, expired)).outcome()).isEqualTo(REJECTED);

        PushJournal altered = new PushJournal(
                CiAutofixAttemptId.random(),
                fixture.inputSha(),
                inspection.localHead().orElseThrow(),
                "changed-fingerprint",
                NOW,
                0);
        PushCiHeadCommand alteredCommand = new PushCiHeadCommand(
                fixture.worktree(),
                REPOSITORY,
                HEAD_REF,
                "origin",
                REPOSITORY,
                HEAD_REF,
                authorization(true),
                altered);
        assertThat(service().push(alteredCommand).outcome()).isEqualTo(REJECTED);
        assertThat(remoteHead(fixture)).isEqualTo(fixture.inputSha());
    }

    @Test
    void rejectsAmbiguousRemoteRoutingBeforePush()
            throws IOException
    {
        Repository fixture = repository("ambiguous-push-routing");
        write(fixture.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(fixture.worktree(), "fixup! Target change");
        CiGitInspection inspection = service().inspectAgentResult(inspect(fixture));
        PushCiHeadCommand command = push(fixture, inspection, 0, authorization(true));
        Path redirected = fixture.remote().getParent().resolve("redirected.git");
        git(fixture.remote().getParent(), "init", "--bare", redirected.toString());

        git(fixture.worktree(), "config", "remote.origin.pushurl", redirected.toUri().toString());
        CiPushResult separatePushUrl = service().push(command);
        assertThat(separatePushUrl.outcome()).isEqualTo(REJECTED);
        assertThat(separatePushUrl.reason()).contains("ambiguous fetch or push routing");
        gitFailure(redirected, "show-ref", "--verify", "refs/heads/" + HEAD_REF);

        git(fixture.worktree(), "config", "--unset-all", "remote.origin.pushurl");
        git(fixture.worktree(), "config", "--add", "remote.origin.url", REMOTE_URL);
        CiPushResult multipleFetchUrls = service().push(command);
        assertThat(multipleFetchUrls.outcome()).isEqualTo(REJECTED);
        assertThat(multipleFetchUrls.reason()).contains("ambiguous fetch or push routing");
        assertThat(remoteHead(fixture)).isEqualTo(fixture.inputSha());
    }

    @Test
    void rejectsMatchingUrlRewritesBeforePush()
            throws IOException
    {
        Repository fixture = repository("push-instead-of");
        write(fixture.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(fixture.worktree(), "fixup! Target change");
        CiGitInspection inspection = service().inspectAgentResult(inspect(fixture));
        PushCiHeadCommand command = push(fixture, inspection, 0, authorization(true));
        Path redirected = fixture.remote().getParent().resolve("redirected.git");
        git(fixture.remote().getParent(), "init", "--bare", redirected.toString());
        for (String rewrite : ImmutableList.of("pushInsteadOf", "insteadOf")) {
            String rewriteKey = "url." + redirected.toUri() + "." + rewrite;
            git(fixture.worktree(), "config", rewriteKey, REMOTE_URL);

            CiPushResult rejected = service().push(command);

            assertThat(rejected.outcome()).isEqualTo(REJECTED);
            assertThat(rejected.reason()).contains("rewrite changes the authorized push destination");
            assertThat(remoteHead(fixture)).isEqualTo(fixture.inputSha());
            gitFailure(redirected, "show-ref", "--verify", "refs/heads/" + HEAD_REF);
            git(fixture.worktree(), "config", "--unset-all", rewriteKey);
        }

        String rewriteKey = "url." + redirected.toUri() + ".pushInsteadOf";
        git(fixture.worktree(), "config", rewriteKey, "https://github.com/other/project.git");
        assertThat(service().push(command).outcome()).isEqualTo(PUSHED);
        assertThat(remoteHead(fixture)).isEqualTo(inspection.localHead().orElseThrow());
        gitFailure(redirected, "show-ref", "--verify", "refs/heads/" + HEAD_REF);
    }

    @Test
    void validatesFixupPositionsWithoutChangingHistory()
            throws IOException
    {
        Repository valid = repository("valid-fixup");
        write(valid.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(valid.worktree(), "fixup! Target change");
        String validHead = head(valid.worktree());
        FixupPositionCheck validResult = service().checkFixupPositions(check(valid));
        assertThat(validResult.result()).isEqualTo(VALID);
        assertThat(head(valid.worktree())).isEqualTo(validHead);

        Repository misplaced = repository("misplaced-fixup");
        write(misplaced.worktree().resolve("next.txt"), "next\n");
        commit(misplaced.worktree(), "Next change");
        write(misplaced.worktree().resolve("source.txt"), "base\ntarget\nlate fix\n");
        commit(misplaced.worktree(), "fixup! Target change");
        assertThat(service().checkFixupPositions(check(misplaced)).result())
                .isEqualTo(FixupPositionCheck.Result.INVALID);

        Repository duplicate = repository("duplicate-fixup");
        write(duplicate.worktree().resolve("source.txt"), "base\ntarget\nfix one\n");
        commit(duplicate.worktree(), "fixup! Target change");
        write(duplicate.worktree().resolve("source.txt"), "base\ntarget\nfix one\nfix two\n");
        commit(duplicate.worktree(), "fixup! Target change");
        assertThat(service().checkFixupPositions(check(duplicate)).result())
                .isEqualTo(FixupPositionCheck.Result.INVALID);
    }

    @Test
    void boundsProcessOutputAndRejectsUnsafeRef()
            throws IOException
    {
        Path executable = temporaryDirectory.resolve("noisy-git");
        write(executable, "#!/bin/sh\nyes x | head -c " + (GitCommandRunner.MAXIMUM_OUTPUT_BYTES + 1) + "\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        GitCommandRunner.Result result = new GitCommandRunner(executable.toString())
                .run(temporaryDirectory, "status");
        assertThat(result.truncated()).isTrue();
        assertThat(result.output().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(GitCommandRunner.MAXIMUM_OUTPUT_BYTES);

        Repository fixture = repository("unsafe-ref");
        write(fixture.worktree().resolve("source.txt"), "base\ntarget\nfix\n");
        commit(fixture.worktree(), "fixup! Target change");
        CiGitInspection inspection = service().inspectAgentResult(inspect(fixture));
        PushCiHeadCommand unsafe = new PushCiHeadCommand(
                fixture.worktree(),
                REPOSITORY,
                HEAD_REF,
                "origin",
                REPOSITORY,
                "feature:refs/heads/other",
                authorization(true),
                journal(fixture, inspection, 0));
        assertThat(service().push(unsafe).outcome()).isEqualTo(REJECTED);
        assertThat(remoteHead(fixture)).isEqualTo(fixture.inputSha());

        Repository credential = repository("credential-origin");
        git(credential.worktree(), "config", "remote.origin.url", "https://secret@github.com/acme/project.git");
        assertInvalid(service(), credential, "unsupported credentials");
    }

    private CiGitOperations service()
    {
        return new CiGitOperations(
                new RoutedGitCommandRunner(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Repository repository(String name)
            throws IOException
    {
        Path root = temporaryDirectory.resolve(name);
        Path remote = root.resolve("remote.git");
        Path worktree = root.resolve("worktree");
        Files.createDirectories(root);
        git(root, "init", "--bare", remote.toString());
        git(root, "init", "-b", HEAD_REF, worktree.toString());
        git(worktree, "config", "user.name", "ByteQuay Test");
        git(worktree, "config", "user.email", "bytequay@example.com");
        git(worktree, "remote", "add", "origin", REMOTE_URL);
        git(
                worktree,
                "config",
                RoutedGitCommandRunner.ROUTE_CONFIG,
                RoutedGitCommandRunner.route(remote, REMOTE_URL));

        write(worktree.resolve("source.txt"), "base\n");
        commit(worktree, "Base");
        String base = head(worktree);
        git(worktree, "push", remote.toString(), "HEAD:refs/heads/main");
        write(worktree.resolve("source.txt"), "base\ntarget\n");
        commit(worktree, "Target change");
        String input = head(worktree);
        git(worktree, "push", remote.toString(), "HEAD:refs/heads/" + HEAD_REF);
        return new Repository(worktree.toRealPath(), remote.toRealPath(), base, input);
    }

    private String advanceRemoteBase(Repository fixture)
            throws IOException
    {
        Path publisher = fixture.remote().getParent().resolve("publisher");
        git(fixture.remote().getParent(), "clone", "--branch", "main", fixture.remote().toString(), publisher.toString());
        git(publisher, "config", "user.name", "ByteQuay Test");
        git(publisher, "config", "user.email", "bytequay@example.com");
        write(publisher.resolve("base-next.txt"), "new base\n");
        commit(publisher, "Advance base");
        git(publisher, "push", "origin", "HEAD:refs/heads/main");
        return head(publisher);
    }

    private static SynchronizeCiBaseCommand synchronize(Repository fixture, String baseSha)
    {
        return new SynchronizeCiBaseCommand(
                fixture.worktree(),
                REPOSITORY,
                HEAD_REF,
                "origin",
                REPOSITORY,
                "main",
                baseSha,
                "bytequay/ci-base/test-run");
    }

    private static InspectAgentResultCommand inspect(Repository fixture)
    {
        return new InspectAgentResultCommand(
                fixture.worktree(),
                REPOSITORY,
                HEAD_REF,
                fixture.inputSha(),
                fixture.baseSha());
    }

    private static CheckFixupPositionsCommand check(Repository fixture)
    {
        return new CheckFixupPositionsCommand(
                fixture.worktree(),
                REPOSITORY,
                HEAD_REF,
                fixture.baseSha(),
                head(fixture.worktree()));
    }

    private static PushCiHeadCommand push(
            Repository fixture,
            CiGitInspection inspection,
            int retries,
            Authorization authorization)
    {
        return new PushCiHeadCommand(
                fixture.worktree(),
                REPOSITORY,
                HEAD_REF,
                "origin",
                REPOSITORY,
                HEAD_REF,
                authorization,
                journal(fixture, inspection, retries));
    }

    private static PushJournal journal(Repository fixture, CiGitInspection inspection, int retries)
    {
        return new PushJournal(
                CiAutofixAttemptId.random(),
                fixture.inputSha(),
                inspection.localHead().orElseThrow(),
                inspection.fingerprint().orElseThrow(),
                NOW,
                retries);
    }

    private static Authorization authorization(boolean allowRewrite)
    {
        return new Authorization(true, allowRewrite, true, 3, NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    private static void assertInvalid(CiGitOperations service, Repository fixture, String message)
    {
        CiGitInspection result = service.inspectAgentResult(inspect(fixture));
        assertThat(result.outcome()).isEqualTo(INVALID);
        assertThat(result.diagnostics()).containsIgnoringCase(message);
        assertThat(result.diagnostics()).hasSizeLessThanOrEqualTo(8 * 1024);
    }

    private static Path gitPath(Path worktree, String state)
    {
        Path path = Path.of(git(worktree, "rev-parse", "--git-path", state));
        return path.isAbsolute() ? path : worktree.resolve(path).normalize();
    }

    private static String remoteHead(Repository fixture)
    {
        String value = git(
                fixture.worktree(),
                "ls-remote",
                fixture.remote().toString(),
                "refs/heads/" + HEAD_REF);
        return value.split("\\s+", 2)[0];
    }

    private static void commit(Path worktree, String message)
    {
        git(worktree, "add", "-A");
        git(worktree, "commit", "-m", message);
    }

    private static String head(Path worktree)
    {
        return git(worktree, "rev-parse", "HEAD");
    }

    private static void write(Path path, String value)
            throws IOException
    {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private static String git(Path worktree, String... arguments)
    {
        GitResult result = runGit(worktree, arguments);
        assertThat(result.exitCode()).as("git %s: %s", String.join(" ", arguments), result.output()).isZero();
        return result.output().strip();
    }

    private static String gitFailure(Path worktree, String... arguments)
    {
        GitResult result = runGit(worktree, arguments);
        assertThat(result.exitCode()).as("git %s", String.join(" ", arguments)).isNotZero();
        return result.output().strip();
    }

    private static GitResult runGit(Path worktree, String... arguments)
    {
        List<String> command = ImmutableList.<String>builder()
                .add("git", "-C", worktree.toString())
                .add(arguments)
                .build();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new GitResult(process.waitFor(), output);
        }
        catch (IOException e) {
            throw new AssertionError("Cannot start Git", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for Git", e);
        }
    }

    private record Repository(Path worktree, Path remote, String baseSha, String inputSha) {}

    private record GitResult(int exitCode, String output) {}
}
