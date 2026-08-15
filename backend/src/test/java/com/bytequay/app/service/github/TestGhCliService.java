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

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
        BoundedProcessRunner runner = mock(BoundedProcessRunner.class);
        when(runner.run(any(), eq(List.of("/fake/gh", "auth", "token")), anyLong(), anyInt()))
                .thenReturn(new BoundedProcessRunner.Result(true, 0, "gho_secret\n", false, null));
        when(runner.run(any(), eq(List.of("/fake/gh", "api", "user", "--jq", ".login")), anyLong(), anyInt()))
                .thenReturn(new BoundedProcessRunner.Result(true, 0, "octocat\n", false, null));

        var credential = new GhCliService(runner).readCredential("/fake/gh");

        assertThat(credential.token()).isEqualTo("gho_secret");
        assertThat(credential.login()).isEqualTo("octocat");
    }

    @Test
    void repeatedTokenReadsAreServedFromTheCache()
            throws Exception
    {
        BoundedProcessRunner runner = mock(BoundedProcessRunner.class);
        when(runner.run(any(), any(), anyLong(), anyInt()))
                .thenReturn(new BoundedProcessRunner.Result(true, 0, "gho_secret\n", false, null));
        GhCliService service = new GhCliService(runner);

        assertThat(service.currentToken("/fake/gh")).hasValue("gho_secret");
        assertThat(service.currentToken("/fake/gh")).hasValue("gho_secret");

        verify(runner, times(1)).run(any(), any(), anyLong(), anyInt());
    }

    @Test
    void aLoggedOutGhYieldsNoLiveTokenRatherThanThrowing()
            throws Exception
    {
        BoundedProcessRunner runner = mock(BoundedProcessRunner.class);
        when(runner.run(any(), any(), anyLong(), anyInt()))
                .thenReturn(new BoundedProcessRunner.Result(true, 1, "gh auth login", false, null));
        GhCliService service = new GhCliService(runner);

        assertThat(service.currentToken("/fake/gh")).isEmpty();
    }

    @Test
    void aLoggedOutGhSurfacesItsOwnMessage()
            throws Exception
    {
        BoundedProcessRunner runner = mock(BoundedProcessRunner.class);
        when(runner.run(any(), any(), anyLong(), anyInt()))
                .thenReturn(new BoundedProcessRunner.Result(
                        true, 1, "To get started with GitHub CLI, please run: gh auth login", false, null));

        assertThatThrownBy(() -> new GhCliService(runner).readCredential("/fake/gh"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("gh auth login");
    }
}
