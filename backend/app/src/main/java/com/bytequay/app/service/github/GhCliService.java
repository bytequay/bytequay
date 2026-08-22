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

import com.bytequay.app.service.credentials.CredentialService;
import com.google.common.collect.ImmutableList;
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

import static java.util.Objects.requireNonNull;

/**
 * Third way to fill the GitHub credential slot: borrow the token the
 * {@code gh} CLI already holds. Useful when an org blocks classic /
 * fine-grained PATs but has approved the GitHub CLI OAuth app — the
 * user's {@code gh} works, so the app can work off the same bearer.
 *
 * Credential persistence and source selection belong to
 * {@link CredentialService}; this class only finds
 * and invokes the CLI.
 */
@Service
public class GhCliService
{
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
    private static final List<Path> PREFERRED_PATHS = ImmutableList.of(
            Path.of("/opt/homebrew/bin/gh"),
            Path.of("/usr/local/bin/gh"));

    private final BoundedProcessRunner processRunner;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public GhCliService(BoundedProcessRunner processRunner)
    {
        this.processRunner = requireNonNull(processRunner, "processRunner is null");
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
     * Reads {@code gh}'s current token and login. Throws 503 when {@code gh}
     * isn't installed, or 502 when it is installed but not logged in.
     */
    public CliCredential readCredential()
    {
        return readCredential(findBinary().orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(503),
                "The GitHub CLI (gh) isn't installed, or isn't in a location ByteQuay looks in "
                        + "(/opt/homebrew/bin, /usr/local/bin, or PATH).")));
    }

    /** Binary-path seam so the read is testable without a real gh on disk. */
    CliCredential readCredential(String gh)
    {
        String token = run(ImmutableList.of(gh, "auth", "token"));
        String login = run(ImmutableList.of(gh, "api", "user", "--jq", ".login"));
        cached.set(new CachedToken(token, Instant.now()));
        return new CliCredential(token, login);
    }

    /**
     * gh's current token, or empty when gh is missing / logged out. Unlike
     * {@link #readCredential()} this never throws — it backs the resolution
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

    /** Binary-path seam, as {@link #readCredential(String)}. */
    Optional<String> currentToken(String gh)
    {
        CachedToken hit = cached.get();
        if (hit != null && Duration.between(hit.readAt(), Instant.now()).compareTo(TOKEN_TTL) < 0) {
            return Optional.of(hit.token());
        }
        try {
            String token = run(ImmutableList.of(gh, "auth", "token"));
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
        BoundedProcessRunner.Result result;
        try {
            // gh resolves its config from HOME; the home dir is also a working
            // dir that always exists, unlike any repo path.
            result = processRunner.run(
                    Path.of(System.getProperty("user.home")), argv, TIMEOUT_SECONDS, MAX_OUTPUT_BYTES);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatusCode.valueOf(503), "gh invocation interrupted", e);
        }
        String output = result.output() == null ? "" : result.output().strip();
        if (result.exitCode() != 0) {
            // The runner folds stderr into stdout, so `output` carries gh's own
            // guidance ("To get started with GitHub CLI, please run: gh auth login").
            String detail = !output.isEmpty() ? output : "exit code " + result.exitCode();
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

    public record CliCredential(String token, String login) {}

    private record CachedToken(String token, Instant readAt) {}
}
