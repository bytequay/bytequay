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
package com.bytequay.app.service.gitlab;

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingServer;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestGlabCliService
{
    private static final HostingServer SERVER = new HostingServer(
            HostingProvider.GITLAB, URI.create("https://gitlab.example.com:8443/tools"), URI.create("https://api.example.com:8443/tools/api/v4"));
    private static final String BINARY = "/fake/glab";
    private static final String SECRET = "test-credential";

    @Test
    void browserLoginBindsServerAndClearsAmbientCredentials()
            throws Exception
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), anyMap(), anyList(), any(), anyInt()))
                .thenReturn(new GitCommandRunner.Result(0, "glab version 1.125.0 (example)\n", false), new GitCommandRunner.Result(0, "", false));
        GlabCliService service = new GlabCliService(runner);

        assertThat(service.provider()).isEqualTo(HostingProvider.GITLAB);
        service.login(SERVER, BINARY);

        verify(runner).runCommand(eq(Path.of(System.getProperty("user.home"))), assertArg(environment -> assertThat(environment)
                        .containsEntry("GITLAB_HOST", "gitlab.example.com:8443").containsEntry("GITLAB_API_HOST", "api.example.com:8443")
                        .containsEntry("GITLAB_SUBFOLDER", "/tools").containsEntry("GLAB_API_PROTOCOL", "https").containsEntry("GLAB_NO_PROMPT", "true")
                        .containsEntry("GITLAB_TOKEN", "").containsEntry("GITLAB_ACCESS_TOKEN", "").containsEntry("OAUTH_TOKEN", "")
                        .containsEntry("CI_JOB_TOKEN", "").containsEntry("GITLAB_REPO", "").containsEntry("CI", "").containsEntry("GITLAB_CI", "")
                        .containsEntry("GLAB_DEBUG_HTTP", "false").containsEntry("GLAB_IS_OAUTH2", "")),
                eq(ImmutableList.of(BINARY, "auth", "login", "--hostname", "gitlab.example.com:8443", "--web", "--use-keyring", "--api-host", "api.example.com:8443",
                        "--api-protocol", "https", "--git-protocol", "https")), eq(Duration.ofMinutes(3)), eq(16 * 1024));
    }

    @Test
    void helperSuppliesCurrentOAuthOrPersonalTokenForTheConfiguredApi()
            throws Exception
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), anyMap(), anyList(), any(), anyInt()))
                .thenReturn(success("https://api.example.com:8443/tools", "oauth2"), success("https://api.example.com:8443/tools/", "pat"));
        GlabCliService service = new GlabCliService(runner);

        assertThat(service.readToken(SERVER, BINARY)).isEqualTo(SECRET);
        assertThat(service.readToken(SERVER, BINARY)).isEqualTo(SECRET);
        verify(runner, times(2)).runCommand(any(), anyMap(), eq(ImmutableList.of(BINARY, "auth", "credential-helper")), eq(Duration.ofSeconds(30)), eq(16 * 1024));
    }

    @Test
    void publicServerOverridesAnySavedSubfolder()
            throws Exception
    {
        HostingServer server = new HostingServer(HostingProvider.GITLAB, URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4"));
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), anyMap(), anyList(), any(), anyInt())).thenReturn(success("https://gitlab.com", "oauth2"));

        assertThat(new GlabCliService(runner).readToken(server, BINARY)).isEqualTo(SECRET);
        verify(runner).runCommand(any(), assertArg(environment -> assertThat(environment).containsEntry("GITLAB_SUBFOLDER", "/")), anyList(), any(), anyInt());
    }

    @Test
    void errorsAndInvalidHelperRepliesNeverExposeCredentials()
            throws Exception
    {
        for (GitCommandRunner.Result result : ImmutableList.of(
                new GitCommandRunner.Result(1, SECRET, false),
                new GitCommandRunner.Result(0, SECRET, true),
                new GitCommandRunner.Result(0, "{\"type\":\"error\",\"message\":\"" + SECRET + "\"}", false),
                new GitCommandRunner.Result(0, "{\"type\":\"success\",\"token\":\"" + SECRET + "\"}", false),
                new GitCommandRunner.Result(0, "{\"type\":\"success\",\"token\":{\"token\":\"" + SECRET, false),
                new GitCommandRunner.Result(0, "null", false),
                new GitCommandRunner.Result(0, "{}", false),
                new GitCommandRunner.Result(0, success("https://api.example.com:8443/tools", "oauth2").output() + " {}", false),
                success("https://api.example.com:8443/tools", "job-token"),
                success("https://other.example.com:8443/tools", "oauth2"),
                success("https://api.example.com:8443/other", "oauth2"),
                success("https://user:password@api.example.com:8443/tools", "oauth2"))) {
            GitCommandRunner runner = mock(GitCommandRunner.class);
            when(runner.runCommand(any(), anyMap(), anyList(), any(), anyInt())).thenReturn(result);

            assertThatThrownBy(() -> new GlabCliService(runner).readToken(SERVER, BINARY))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Sign in again").hasMessageNotContaining(SECRET).hasNoCause();
        }
    }

    @Test
    void oldOrUnknownCliVersionDoesNotStartLogin()
            throws Exception
    {
        for (String version : ImmutableList.of("glab version 1.124.0", "glab 0.125.0", "glab development " + SECRET, "glab 99999999999999999999999.0.0")) {
            GitCommandRunner runner = mock(GitCommandRunner.class);
            when(runner.runCommand(any(), anyMap(), anyList(), any(), anyInt())).thenReturn(new GitCommandRunner.Result(0, version, false));

            assertThatThrownBy(() -> new GlabCliService(runner).login(SERVER, BINARY))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("1.125").hasMessageNotContaining(SECRET).hasNoCause();
            verify(runner, never()).runCommand(any(), anyMap(), anyList(), eq(Duration.ofMinutes(3)), anyInt());
        }
    }

    @Test
    void failedLoginDoesNotExposeCliOutput()
            throws Exception
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), anyMap(), anyList(), any(), anyInt()))
                .thenReturn(new GitCommandRunner.Result(0, "glab 1.125.0", false), new GitCommandRunner.Result(1, SECRET, false));

        assertThatThrownBy(() -> new GlabCliService(runner).login(SERVER, BINARY))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("client_id").hasMessageNotContaining(SECRET).hasNoCause();
    }

    @Test
    void unsupportedServerRoutesUseTheTokenFallback()
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        for (HostingServer server : ImmutableList.of(
                new HostingServer(HostingProvider.GITLAB, URI.create("http://localhost:8080"), URI.create("http://localhost:8080/api/v4")),
                new HostingServer(HostingProvider.GITLAB, SERVER.webBaseUrl(), URI.create("https://api.example.com/different/api/v4")),
                new HostingServer(HostingProvider.GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com")))) {
            assertThatThrownBy(() -> new GlabCliService(runner).readToken(server, BINARY))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("personal access token");
        }
        verifyNoInteractions(runner);
    }

    @Test
    void interruptedHelperPreservesInterruptionWithoutExposingOutput()
            throws Exception
    {
        GitCommandRunner runner = mock(GitCommandRunner.class);
        when(runner.runCommand(any(), anyMap(), anyList(), any(), anyInt())).thenThrow(new InterruptedException(SECRET));
        try {
            assertThatThrownBy(() -> new GlabCliService(runner).readToken(SERVER, BINARY))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("interrupted").hasMessageNotContaining(SECRET).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }

    private static GitCommandRunner.Result success(String instance, String kind)
    {
        return new GitCommandRunner.Result(0, """
                {"type":"success","instance_url":"%s","token":{"type":"%s","token":"%s","expiry_timestamp":"2026-01-01T00:00:00Z"}}
                """.formatted(instance, kind, SECRET), false);
    }
}
