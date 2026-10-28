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
import com.bytequay.app.json.JsonContracts;
import com.bytequay.app.service.hosting.HostingCli;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.HostingUrls;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Service
public class GlabCliService
        implements HostingCli
{
    private static final ObjectMapper MAPPER = JsonContracts.mapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Pattern VERSION = Pattern.compile("glab(?: version)? v?(\\d+)\\.(\\d+)\\.\\d+\\b");
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final String UPGRADE = "Install or update GitLab CLI 1.125 or newer with brew install glab or brew upgrade glab, or use a personal access token.";
    private static final String READ_FAILURE = "GitLab CLI could not provide a credential for this server. Sign in again or use a personal access token.";

    private final GitCommandRunner runner;

    public GlabCliService(GitCommandRunner runner)
    {
        this.runner = requireNonNull(runner, "runner is null");
    }

    @Override
    public HostingProvider provider()
    {
        return HostingProvider.GITLAB;
    }

    public GitLabCliStatus status()
    {
        boolean ready = findBinary("glab").map(binary -> supportedVersion(run(
                ImmutableMap.of("GLAB_CHECK_UPDATE", "false", "GLAB_SHOW_WHATS_NEW", "false"),
                ImmutableList.of(binary, "--version"), READ_TIMEOUT, UPGRADE))).orElse(false);
        return new GitLabCliStatus(ready, findBinary("brew").isPresent());
    }

    public GitLabCliStatus install()
    {
        String brew = findBinary("brew").orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Install Homebrew from brew.sh, then check again, or use a personal access token."));
        try {
            GitCommandRunner.Result result = runner.runCommand(Path.of(System.getProperty("user.home")),
                    ImmutableMap.of("HOMEBREW_NO_ASK", "1", "HOMEBREW_NO_INSTALL_UPGRADE", "", "HOMEBREW_NO_INSTALL_CLEANUP", "1"),
                    ImmutableList.of(brew, "install", "glab"), Duration.ofMinutes(10), MAX_OUTPUT_BYTES);
            // Installation output is not parsed; a successful install may exceed the capture limit.
            if (!result.successful()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitLab CLI installation failed. Run brew install glab in Terminal for details, then check again.");
            }
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitLab CLI installation was interrupted. Check again before retrying.");
        }
        GitLabCliStatus status = status();
        if (!status.ready()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitLab CLI is still unavailable after installation. " + UPGRADE);
        }
        return status;
    }

    @Override
    public void login(HostingServer server)
    {
        login(server, binary());
    }

    void login(HostingServer server, String binary)
    {
        Map<String, String> environment = environment(server);
        String version = run(environment, ImmutableList.of(binary, "--version"), READ_TIMEOUT, UPGRADE);
        if (!supportedVersion(version)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UPGRADE);
        }
        run(environment, ImmutableList.of(binary, "auth", "login", "--hostname", server.webBaseUrl().getAuthority(), "--web", "--use-keyring",
                        "--api-host", server.apiBaseUrl().getAuthority(), "--api-protocol", "https", "--git-protocol", "https"),
                Duration.ofMinutes(3), "GitLab browser sign-in did not complete. Try again or use a personal access token. Private servers need an OAuth client_id in glab.");
    }

    @Override
    public String readToken(HostingServer server)
    {
        return readToken(server, binary());
    }

    String readToken(HostingServer server, String binary)
    {
        String output = run(environment(server), ImmutableList.of(binary, "auth", "credential-helper"), READ_TIMEOUT, READ_FAILURE);
        try {
            HelperResponse response = Optional.ofNullable(MAPPER.readValue(output, HelperResponse.class)).orElseThrow();
            URI expected = URI.create(server.apiBaseUrl().toString().replaceFirst("/api/v4$", ""));
            URI instance = HostingUrls.canonicalBaseUrl(URI.create(response.instanceUrl().orElseThrow()));
            HelperToken credential = response.token().orElseThrow();
            String token = credential.token().orElseThrow();
            checkArgument("success".equals(response.type()) && expected.equals(instance) &&
                    ("pat".equals(credential.type()) || "oauth2".equals(credential.type())) && !token.isBlank() && token.codePoints().noneMatch(Character::isISOControl));
            return token;
        }
        catch (JsonProcessingException | RuntimeException failure) {
            // CLI output and parser exceptions can contain the credential.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, READ_FAILURE);
        }
    }

    private String run(Map<String, String> environment, List<String> command, Duration timeout, String failureMessage)
    {
        try {
            GitCommandRunner.Result result = runner.runCommand(Path.of(System.getProperty("user.home")), environment, command, timeout, MAX_OUTPUT_BYTES);
            if (!result.successful() || result.truncated()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, failureMessage);
            }
            return result.output().strip();
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitLab CLI was interrupted. Try again.");
        }
    }

    private static Map<String, String> environment(HostingServer server)
    {
        if (server.provider() != HostingProvider.GITLAB || !server.webBaseUrl().getScheme().equals("https") || !server.apiBaseUrl().getScheme().equals("https") ||
                !server.apiBaseUrl().getPath().equals(server.webBaseUrl().getPath() + "/api/v4")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitLab CLI requires HTTPS and /api/v4 under the server path. Use a personal access token for this server.");
        }
        return ImmutableMap.<String, String>builder()
                .put("GITLAB_HOST", server.webBaseUrl().getAuthority())
                .put("GITLAB_API_HOST", server.apiBaseUrl().getAuthority())
                .put("GITLAB_SUBFOLDER", server.webBaseUrl().getPath().isEmpty() ? "/" : server.webBaseUrl().getPath())
                .put("GLAB_API_PROTOCOL", "https")
                .put("GLAB_NO_PROMPT", "true")
                .put("GLAB_CHECK_UPDATE", "false")
                .put("GLAB_SHOW_WHATS_NEW", "false")
                .put("GLAB_DEBUG", "false")
                .put("GLAB_DEBUG_HTTP", "false")
                .put("GLAB_ENABLE_CI_AUTOLOGIN", "false")
                .put("GITLAB_TOKEN", "").put("GITLAB_ACCESS_TOKEN", "").put("OAUTH_TOKEN", "")
                .put("CI_JOB_TOKEN", "").put("JOB_TOKEN", "").put("GLAB_IS_OAUTH2", "")
                .put("GITLAB_REPO", "").put("GIT_DIR", "").put("GIT_WORK_TREE", "")
                .put("CI", "").put("GITLAB_CI", "")
                .buildOrThrow();
    }

    private String binary()
    {
        return findBinary("glab").orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UPGRADE));
    }

    Optional<String> findBinary(String name)
    {
        Stream<Path> fromPath = Stream.of(System.getenv().getOrDefault("PATH", "").split(":"))
                .filter(directory -> !directory.isBlank()).map(directory -> Path.of(directory).resolve(name));
        return Stream.concat(Stream.of(Path.of("/opt/homebrew/bin", name), Path.of("/usr/local/bin", name)), fromPath)
                .filter(Files::isExecutable).map(Path::toString).findFirst();
    }

    private static boolean supportedVersion(String version)
    {
        Matcher match = VERSION.matcher(version);
        try {
            return match.find() && (Integer.parseInt(match.group(1)) > 1 || Integer.parseInt(match.group(1)) == 1 && Integer.parseInt(match.group(2)) >= 125);
        }
        catch (NumberFormatException failure) {
            return false;
        }
    }

    private record HelperResponse(String type, @JsonProperty("instance_url") Optional<String> instanceUrl, Optional<HelperToken> token)
    {
        @Override
        public String toString()
        {
            return "HelperResponse[redacted]";
        }
    }

    private record HelperToken(String type, Optional<String> token)
    {
        @Override
        public String toString()
        {
            return "HelperToken[redacted]";
        }
    }
}
