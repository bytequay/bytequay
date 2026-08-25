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
package com.bytequay.app.service.github;

import com.bytequay.app.git.GitCommandRunner;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gh-CLI adapter is one subprocess pair away from a bearer, so the
 * checks that matter are the returned values and preserved failure text.
 */
class TestGhCliService
{
    @Test
    void credentialReadReturnsTheTrimmedTokenAndLogin()
            throws Exception
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), eq(ImmutableList.of("/fake/gh", "auth", "token")), eq(Duration.ofSeconds(20)), eq(8192)))
                .thenReturn(new GitCommandRunner.Result(0, "gho_secret\n", false));
        when(runner.runCommand(any(), eq(ImmutableList.of("/fake/gh", "api", "user", "--jq", ".login")), eq(Duration.ofSeconds(20)), eq(8192)))
                .thenReturn(new GitCommandRunner.Result(0, "octocat\n", false));

        var credential = new GhCliService(runner).readCredential("/fake/gh");

        assertThat(credential.token()).isEqualTo("gho_secret");
        assertThat(credential.login()).isEqualTo("octocat");
    }

    @Test
    void repeatedTokenReadsAreServedFromTheCache()
            throws Exception
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), any(), any(), eq(8192)))
                .thenReturn(new GitCommandRunner.Result(0, "gho_secret\n", false));
        GhCliService service = new GhCliService(runner);

        assertThat(service.currentToken("/fake/gh")).hasValue("gho_secret");
        assertThat(service.currentToken("/fake/gh")).hasValue("gho_secret");

        verify(runner, times(1)).runCommand(any(), any(), any(), eq(8192));
    }

    @Test
    void aLoggedOutGhYieldsNoLiveTokenRatherThanThrowing()
            throws Exception
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), any(), any(), eq(8192)))
                .thenReturn(new GitCommandRunner.Result(1, "gh auth login", false));
        GhCliService service = new GhCliService(runner);

        assertThat(service.currentToken("/fake/gh")).isEmpty();
    }

    @Test
    void aLoggedOutGhSurfacesItsOwnMessage()
            throws Exception
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), any(), any(), eq(8192)))
                .thenReturn(new GitCommandRunner.Result(
                        1, "To get started with GitHub CLI, please run: gh auth login", false));

        assertThatThrownBy(() -> new GhCliService(runner).readCredential("/fake/gh"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("gh auth login");
    }
}
