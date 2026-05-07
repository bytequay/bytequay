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
package com.bytequay.app.service.local;

import com.bytequay.app.domain.LocalActivityEntry;
import com.bytequay.app.domain.LocalBranch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestLocalRepoService
{
    @Test
    void testRemoteMatchesHttpsWithDotGit()
    {
        assertThat(LocalRepoService.remoteMatchesRepo(
                "https://github.com/trinodb/trino.git", "trinodb", "trino")).isTrue();
    }

    @Test
    void testRemoteMatchesHttpsWithoutDotGit()
    {
        assertThat(LocalRepoService.remoteMatchesRepo(
                "https://github.com/trinodb/trino", "trinodb", "trino")).isTrue();
    }

    @Test
    void testRemoteMatchesSsh()
    {
        assertThat(LocalRepoService.remoteMatchesRepo(
                "git@github.com:trinodb/trino.git", "trinodb", "trino")).isTrue();
    }

    @Test
    void testRemoteMatchesIsCaseInsensitive()
    {
        // GitHub URLs are case-insensitive on the path; honor that.
        assertThat(LocalRepoService.remoteMatchesRepo(
                "https://github.com/TrinoDB/Trino.git", "trinodb", "trino")).isTrue();
    }

    @Test
    void testRemoteMismatchOnDifferentRepo()
    {
        assertThat(LocalRepoService.remoteMatchesRepo(
                "https://github.com/trinodb/trino-doc.git", "trinodb", "trino")).isFalse();
    }

    @Test
    void testRemoteMismatchOnDifferentOwner()
    {
        // Common case: user located their fork instead of the upstream.
        assertThat(LocalRepoService.remoteMatchesRepo(
                "https://github.com/chenjian2664/trino.git", "trinodb", "trino")).isFalse();
    }

    @Test
    void testRemoteMismatchOnNonGithubHost()
    {
        // gitlab clone of the same repo is still rejected — the watched
        // repo is github.com/owner/repo, mirrors don't count.
        assertThat(LocalRepoService.remoteMatchesRepo(
                "https://gitlab.com/trinodb/trino.git", "trinodb", "trino")).isFalse();
    }

    @Test
    void testRemoteMismatchOnEmptyString()
    {
        assertThat(LocalRepoService.remoteMatchesRepo("", "trinodb", "trino")).isFalse();
    }

    @Test
    void testRedactStripsHttpsCredentials()
    {
        // Embedded PAT — common when GH CLI sets up the remote.
        assertThat(LocalRepoService.redactCredentials(
                "https://ghp_abcdef@github.com/chenjian2664/trino_new.git"))
                .isEqualTo("https://github.com/chenjian2664/trino_new.git");
    }

    @Test
    void testRedactStripsUserPasswordCredentials()
    {
        assertThat(LocalRepoService.redactCredentials(
                "https://alice:s3cret@github.com/foo/bar.git"))
                .isEqualTo("https://github.com/foo/bar.git");
    }

    @Test
    void testRedactPreservesSshForm()
    {
        // SSH form has a literal "git@" prefix that is NOT a credential.
        assertThat(LocalRepoService.redactCredentials(
                "git@github.com:trinodb/trino.git"))
                .isEqualTo("git@github.com:trinodb/trino.git");
    }

    @Test
    void testRedactPreservesHttpsWithoutCredentials()
    {
        assertThat(LocalRepoService.redactCredentials(
                "https://github.com/trinodb/trino.git"))
                .isEqualTo("https://github.com/trinodb/trino.git");
    }

    @Test
    void testClassifyCommitSubject()
    {
        assertThat(LocalRepoService.classifyReflogSubject("commit: WIP on auth"))
                .isEqualTo(LocalActivityEntry.Kind.COMMIT);
    }

    @Test
    void testClassifyInitialCommit()
    {
        // git emits "commit (initial):" for the first commit in a repo —
        // the parens trip the simple split-on-colon, so we need the
        // startsWith fallback to catch it.
        assertThat(LocalRepoService.classifyReflogSubject("commit (initial): bootstrap"))
                .isEqualTo(LocalActivityEntry.Kind.COMMIT);
    }

    @Test
    void testClassifyAmendCommit()
    {
        assertThat(LocalRepoService.classifyReflogSubject("commit (amend): fixup tests"))
                .isEqualTo(LocalActivityEntry.Kind.COMMIT);
    }

    @Test
    void testClassifyCheckout()
    {
        assertThat(LocalRepoService.classifyReflogSubject("checkout: moving from main to feat/foo"))
                .isEqualTo(LocalActivityEntry.Kind.CHECKOUT);
    }

    @Test
    void testClassifyPull()
    {
        assertThat(LocalRepoService.classifyReflogSubject("pull: Fast-forward"))
                .isEqualTo(LocalActivityEntry.Kind.PULL);
    }

    @Test
    void testClassifyMerge()
    {
        assertThat(LocalRepoService.classifyReflogSubject("merge feat/foo: Merge made by 'ort'."))
                .isEqualTo(LocalActivityEntry.Kind.MERGE);
    }

    @Test
    void testClassifyRebaseInteractive()
    {
        // Interactive rebase emits "rebase -i (start):" / "(finish):" —
        // exercises the parenthesized-subject fallback.
        assertThat(LocalRepoService.classifyReflogSubject("rebase -i (start): checkout origin/main"))
                .isEqualTo(LocalActivityEntry.Kind.REBASE);
    }

    @Test
    void testClassifyUnknownFallsThrough()
    {
        assertThat(LocalRepoService.classifyReflogSubject("garbage: never seen"))
                .isEqualTo(LocalActivityEntry.Kind.UNKNOWN);
    }

    @Test
    void testClassifyEmptySubject()
    {
        assertThat(LocalRepoService.classifyReflogSubject("")).isEqualTo(LocalActivityEntry.Kind.UNKNOWN);
        assertThat(LocalRepoService.classifyReflogSubject(null)).isEqualTo(LocalActivityEntry.Kind.UNKNOWN);
    }

    @Test
    void testCleanupRemoteGoneWins()
    {
        // Even with a recent commit, [gone] upstream is the canonical
        // post-merge cleanup signal — flag it.
        Instant recent = Instant.now().minus(Duration.ofDays(1));
        assertThat(LocalRepoService.classifyCleanup(recent, true, true))
                .isEqualTo(LocalBranch.CleanupReason.REMOTE_GONE);
    }

    @Test
    void testCleanupNeverPushedAndIdle()
    {
        Instant ancient = Instant.now().minus(Duration.ofDays(120));
        assertThat(LocalRepoService.classifyCleanup(ancient, false, false))
                .isEqualTo(LocalBranch.CleanupReason.IDLE_NEVER_PUSHED);
    }

    @Test
    void testCleanupNeverPushedButRecent()
    {
        // 30d active branch — user is still iterating; don't flag.
        Instant recent = Instant.now().minus(Duration.ofDays(30));
        assertThat(LocalRepoService.classifyCleanup(recent, false, false)).isNull();
    }

    @Test
    void testCleanupPushedAndCurrent()
    {
        // Healthy tracking branch with an upstream still alive — never
        // a cleanup candidate regardless of age.
        Instant ancient = Instant.now().minus(Duration.ofDays(365));
        assertThat(LocalRepoService.classifyCleanup(ancient, true, false)).isNull();
    }

    @Test
    void testCleanupNullTimestampSkipsIdleCheck()
    {
        // We can't decide "is it idle" without a timestamp — bail out
        // rather than flagging on incomplete info.
        assertThat(LocalRepoService.classifyCleanup(null, false, false)).isNull();
    }
}
