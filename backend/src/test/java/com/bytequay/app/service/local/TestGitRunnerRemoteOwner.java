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

import com.bytequay.app.domain.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-string coverage for {@link GitRunner#parseRepoOwner} — the owner
 * extractor that forms a cross-fork PR head ({@code <fork-owner>:branch})
 * from the clone's {@code origin} URL. Covers the three remote-URL forms
 * git emits so a fork PR targets the right head regardless of how the
 * user cloned.
 */
class TestGitRunnerRemoteOwner
{
    @Test
    void parsesHttpsUrlWithAndWithoutGitSuffix()
    {
        assertThat(GitRunner.parseRepoOwner("https://github.com/trinodb/trino.git"))
                .contains("trinodb");
        assertThat(GitRunner.parseRepoOwner("https://github.com/chenjian2664/bytequay"))
                .contains("chenjian2664");
    }

    @Test
    void parsesScpLikeSshUrl()
    {
        assertThat(GitRunner.parseRepoOwner("git@github.com:trinodb/trino.git"))
                .contains("trinodb");
    }

    @Test
    void parsesSshSchemeUrl()
    {
        assertThat(GitRunner.parseRepoOwner("ssh://git@github.com/trinodb/trino.git"))
                .contains("trinodb");
    }

    @Test
    void returnsEmptyForBlankOrShapelessInput()
    {
        assertThat(GitRunner.parseRepoOwner(null)).isEmpty();
        assertThat(GitRunner.parseRepoOwner("")).isEmpty();
        assertThat(GitRunner.parseRepoOwner("   ")).isEmpty();
        // No owner segment — only a single path component.
        assertThat(GitRunner.parseRepoOwner("https://example.com/repo")).isEmpty();
    }

    @Test
    void ownerIsTheSegmentBeforeTheRepo()
    {
        // Enterprise host with a deeper path still resolves the owner as
        // the segment immediately preceding the repo.
        Optional<String> owner = GitRunner.parseRepoOwner("https://ghe.corp/myorg/svc.git");
        assertThat(owner).contains("myorg");
    }

    @Test
    void parseRepoSlugYieldsOwnerAndRepoAcrossUrlForms()
    {
        // The local-PR push needs the full owner/repo to open the PR.
        assertThat(GitRunner.parseRepoSlug("https://github.com/trinodb/trino.git"))
                .contains(new RepoRef("trinodb", "trino"));
        assertThat(GitRunner.parseRepoSlug("git@github.com:chenjian2664/bytequay.git"))
                .contains(new RepoRef("chenjian2664", "bytequay"));
        assertThat(GitRunner.parseRepoSlug("ssh://git@github.com/acme/widget"))
                .contains(new RepoRef("acme", "widget"));
    }

    @Test
    void parseRepoSlugReturnsEmptyForShapelessInput()
    {
        assertThat(GitRunner.parseRepoSlug(null)).isEmpty();
        assertThat(GitRunner.parseRepoSlug("")).isEmpty();
        assertThat(GitRunner.parseRepoSlug("https://example.com/repo")).isEmpty();
    }
}
