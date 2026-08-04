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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.local.ShellRunner;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.bytequay.app.repository.CredentialStore.DEFAULT_INSTANCE_NAME;
import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
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
 * The gh-CLI import is one subprocess pair away from a stored bearer, so
 * the checks that matter are: the trimmed token reaches the account slot,
 * and gh's own failure text survives to the user instead of a bare status.
 */
class TestGhCliService
{
    @Test
    void importStoresTheTrimmedTokenUnderTheAccountSlot()
            throws Exception
    {
        CredentialService credentials = mock(CredentialService.class);
        ShellRunner runner = mock(ShellRunner.class);
        when(runner.runArgv(any(), eq(List.of("/fake/gh", "auth", "token")), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(true, 0, "gho_secret\n", false, null));
        when(runner.runArgv(any(), eq(List.of("/fake/gh", "api", "user", "--jq", ".login")), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(true, 0, "octocat\n", false, null));

        var info = new GhCliService(credentials, runner).importToken("/fake/gh");

        assertThat(info.login()).isEqualTo("octocat");
        verify(credentials).upsert(
                eq(CredentialType.ACCOUNT),
                eq(GITHUB_ACCOUNT_NAME),
                eq(DEFAULT_INSTANCE_NAME),
                eq("gho_secret"),
                eq("octocat"),
                any(),
                eq(GhCliService.SOURCE_MARKER));
    }

    @Test
    void repeatedTokenReadsAreServedFromTheCache()
            throws Exception
    {
        ShellRunner runner = mock(ShellRunner.class);
        when(runner.runArgv(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(true, 0, "gho_secret\n", false, null));
        GhCliService service = new GhCliService(mock(CredentialService.class), runner);

        assertThat(service.currentToken("/fake/gh")).hasValue("gho_secret");
        assertThat(service.currentToken("/fake/gh")).hasValue("gho_secret");

        verify(runner, times(1)).runArgv(any(), any(), anyLong(), anyInt());
    }

    @Test
    void aLoggedOutGhYieldsNoLiveTokenRatherThanThrowing()
            throws Exception
    {
        ShellRunner runner = mock(ShellRunner.class);
        when(runner.runArgv(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(true, 1, "gh auth login", false, null));
        GhCliService service = new GhCliService(mock(CredentialService.class), runner);

        assertThat(service.currentToken("/fake/gh")).isEmpty();
    }

    @Test
    void aLoggedOutGhSurfacesItsOwnMessage()
            throws Exception
    {
        CredentialService credentials = mock(CredentialService.class);
        ShellRunner runner = mock(ShellRunner.class);
        when(runner.runArgv(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(
                        true, 1, "To get started with GitHub CLI, please run: gh auth login", false, null));

        assertThatThrownBy(() -> new GhCliService(credentials, runner).importToken("/fake/gh"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("gh auth login");
    }
}
