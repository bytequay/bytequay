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
import com.bytequay.app.service.github.GitHubOAuthService.ConnectionInfo;
import com.bytequay.app.service.local.ShellRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.bytequay.app.repository.CredentialStore.DEFAULT_INSTANCE_NAME;
import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
import static java.util.Objects.requireNonNull;

/**
 * Third way to fill the GitHub credential slot: borrow the token the
 * {@code gh} CLI already holds. Useful when an org blocks classic /
 * fine-grained PATs but has approved the GitHub CLI OAuth app — the
 * user's {@code gh} works, so the app can work off the same bearer.
 *
 * <p>Like {@link GitHubOAuthService}, the token lands in
 * {@code (ACCOUNT, "github")}, so {@code PatResolver} and every REST /
 * GraphQL call downstream are unchanged — no separate "shell out to gh
 * for each API call" transport exists or is needed.
 *
 * <p>The import is a snapshot, not a live binding: {@code gh auth token}
 * runs once per click. If the user re-runs {@code gh auth login} and the
 * token rotates, they re-import.
 */
@Service
public class GhCliService
{
    /** Written to the account credential's {@code configJson} so
     *  {@code PatResolver} knows the stored token is a copy of gh's and can
     *  prefer gh's live one. ACCOUNT rows otherwise ignore that column. */
    public static final String SOURCE_MARKER = "{\"source\":\"gh-cli\"}";

    private static final Logger log = LoggerFactory.getLogger(GhCliService.class);

    /** How long a live token read is reused. Short enough that a
     *  {@code gh auth login} rotation heals within a coffee break, long
     *  enough that one dashboard refresh reads the keychain once. */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    /** {@code gh auth token} reads the keychain; {@code gh api user} makes one
     *  HTTP call. 20 s covers a slow keychain prompt without hanging a click. */
    private static final long TIMEOUT_SECONDS = 20L;
    private static final int MAX_OUTPUT_BYTES = 8 * 1024;

    /** A packaged Electron app inherits the GUI's minimal PATH
     *  (/usr/bin:/bin:/usr/sbin:/sbin), so the usual install locations are
     *  probed explicitly before falling back to PATH. */
    private static final List<Path> PREFERRED_PATHS = List.of(
            Path.of("/opt/homebrew/bin/gh"),
            Path.of("/usr/local/bin/gh"));

    private final CredentialService credentialService;
    private final ShellRunner shellRunner;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public GhCliService(CredentialService credentialService, ShellRunner shellRunner)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.shellRunner = requireNonNull(shellRunner, "shellRunner is null");
    }

    /** Absolute path to the {@code gh} binary, or empty when it isn't installed. */
    public Optional<String> findBinary()
    {
        String path = System.getenv("PATH");
        Stream<Path> fromPath = path == null ? Stream.empty() : Stream.of(path.split(":"))
                .filter(directory -> !directory.isBlank())
                .map(directory -> Path.of(directory).resolve("gh"));
        return Stream.concat(PREFERRED_PATHS.stream(), fromPath)
                .filter(Files::isExecutable)
                .findFirst()
                .map(Path::toString);
    }

    public boolean isAvailable()
    {
        return findBinary().isPresent();
    }

    /**
     * Reads {@code gh}'s current token and stores it as the account
     * credential. Throws 503 when {@code gh} isn't installed, 502 when
     * {@code gh} is installed but not logged in (its own message is
     * surfaced verbatim).
     */
    public ConnectionInfo importToken()
    {
        return importToken(findBinary().orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(503),
                "The GitHub CLI (gh) isn't installed, or isn't in a location ByteQuay looks in "
                        + "(/opt/homebrew/bin, /usr/local/bin, or PATH).")));
    }

    /** Binary-path seam so the import is testable without a real gh on disk. */
    ConnectionInfo importToken(String gh)
    {
        String token = run(List.of(gh, "auth", "token"));
        String login = run(List.of(gh, "api", "user", "--jq", ".login"));
        credentialService.upsert(
                CredentialType.ACCOUNT,
                GITHUB_ACCOUNT_NAME,
                DEFAULT_INSTANCE_NAME,
                token,
                login,
                "Imported from the GitHub CLI on " + Instant.now(),
                SOURCE_MARKER);
        cached.set(new CachedToken(token, Instant.now()));
        log.info("Imported GitHub CLI token for login={}", login);
        return new ConnectionInfo(login);
    }

    /**
     * gh's current token, or empty when gh is missing / logged out. Unlike
     * {@link #importToken()} this never throws — it backs the resolution
     * fallback, where a dead gh should degrade to the stored copy rather
     * than fail the request.
     *
     * <p>Cached for {@link #TOKEN_TTL} so a dashboard refresh (dozens of API
     * calls) doesn't spawn a keychain-reading subprocess per call.
     */
    public Optional<String> currentToken()
    {
        return findBinary().flatMap(this::currentToken);
    }

    /** Binary-path seam, as {@link #importToken(String)}. */
    Optional<String> currentToken(String gh)
    {
        CachedToken hit = cached.get();
        if (hit != null && Duration.between(hit.readAt(), Instant.now()).compareTo(TOKEN_TTL) < 0) {
            return Optional.of(hit.token());
        }
        try {
            String token = run(List.of(gh, "auth", "token"));
            cached.set(new CachedToken(token, Instant.now()));
            return Optional.of(token);
        }
        catch (ResponseStatusException e) {
            // Logged out, or the keychain is locked. run() already logged the
            // detail; the caller falls back to whatever is stored.
            return Optional.empty();
        }
    }

    /** Runs one gh subcommand and returns its trimmed stdout. */
    private String run(List<String> argv)
    {
        ShellRunner.Result result;
        try {
            // gh resolves its config from HOME; the home dir is also a working
            // dir that always exists, unlike any repo path.
            result = shellRunner.runArgv(
                    Path.of(System.getProperty("user.home")), argv, TIMEOUT_SECONDS, MAX_OUTPUT_BYTES);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatusCode.valueOf(503), "gh invocation interrupted", e);
        }
        String output = result.output() == null ? "" : result.output().strip();
        if (!result.ran() || result.exitCode() != 0) {
            // ShellRunner folds stderr into stdout, so `output` carries gh's own
            // guidance ("To get started with GitHub CLI, please run: gh auth login").
            String detail = !output.isEmpty() ? output : result.error();
            log.warn("gh {} failed: {}", argv.subList(1, argv.size()), detail);
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "The GitHub CLI couldn't provide a token: " + detail);
        }
        if (output.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "The GitHub CLI returned nothing for `gh " + String.join(" ", argv.subList(1, argv.size())) + "`.");
        }
        return output;
    }

    private record CachedToken(String token, Instant readAt) {}
}
