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

import org.junit.jupiter.api.Test;

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
}
