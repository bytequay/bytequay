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
package com.bytequay.app.service.mcp.approval;

import com.google.common.collect.ImmutableSet;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Flags a shell command that would push code or mutate PR / release /
 * review state on GitHub <em>directly</em> — bypassing ByteQuay's publish
 * tools and the "nothing reaches GitHub without an explicit user action"
 * invariant. {@link DenyRemoteGitStep} uses this to hard-deny such a call
 * and point the agent at the right tool ({@code push}, {@code open_pr},
 * {@code merge_pr}, …) instead.
 *
 * <p>What is flagged (the approved deny-list):
 * <ul>
 *   <li>{@code git push} — pushing the branch</li>
 *   <li>{@code git remote add} / {@code set-url} / {@code remove} /
 *       {@code rename} — rewiring where the repo points</li>
 *   <li>{@code gh pr create|merge|ready|edit|close|reopen|review|comment}</li>
 *   <li>{@code gh release create|edit|delete|upload}</li>
 *   <li>{@code gh api} with an explicit mutating method
 *       ({@code -X POST|PUT|PATCH|DELETE} / {@code --method …})</li>
 *   <li>{@code curl} / {@code wget} that targets {@code github.com} or
 *       {@code api.github.com}</li>
 * </ul>
 *
 * <p>Left alone (read / local work is fine): {@code git status|diff|log|
 * commit|fetch|…}, {@code gh pr view|list|diff|checks}, {@code gh api}
 * without a mutating method (a GET read), and any network call to a
 * non-GitHub host. The scan splits the command on sequencing operators
 * ({@code ; && || |} and newlines) so {@code cd repo && git push} is
 * caught, and resolves {@code git}'s global flags ({@code git -C dir
 * push}) before reading the subcommand.
 */
public final class RemoteGitClassifier
{
    private RemoteGitClassifier() {}

    /** A blocked command and the tool the agent should use instead. */
    public record Match(String blocked, String useInstead) {}

    private static final Set<String> GIT_REMOTE_MUTATIONS = ImmutableSet.of(
            "add", "set-url", "remove", "rename");

    private static final Set<String> GH_PR_MUTATIONS = ImmutableSet.of(
            "create", "merge", "ready", "edit", "close", "reopen", "review", "comment");

    private static final Set<String> GH_RELEASE_MUTATIONS = ImmutableSet.of(
            "create", "edit", "delete", "upload");

    private static final Set<String> MUTATING_HTTP_METHODS = ImmutableSet.of(
            "POST", "PUT", "PATCH", "DELETE");

    /**
     * The first remote-mutating segment of {@code command}, if any.
     * Empty when the command is purely read / local work.
     */
    public static Optional<Match> findRemoteMutation(String command)
    {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        for (String segment : command.split("&&|\\|\\||;|\\||\\n|\\r")) {
            Optional<Match> match = classifySegment(segment.strip());
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private static Optional<Match> classifySegment(String segment)
    {
        if (segment.isEmpty()) {
            return Optional.empty();
        }
        String[] tokens = segment.split("\\s+");
        String cmd = basename(tokens[0]);
        return switch (cmd) {
            case "git" -> classifyGit(tokens);
            case "gh" -> classifyGh(tokens);
            case "curl", "wget" -> classifyHttp(tokens);
            default -> Optional.empty();
        };
    }

    private static Optional<Match> classifyGit(String[] tokens)
    {
        int sub = gitSubcommandIndex(tokens);
        if (sub < 0) {
            return Optional.empty();
        }
        String subcommand = tokens[sub];
        if (subcommand.equals("push")) {
            return Optional.of(new Match(
                    "git push",
                    "the `push` tool to push your branch (then `open_pr` to open the PR)"));
        }
        if (subcommand.equals("remote")
                && sub + 1 < tokens.length
                && GIT_REMOTE_MUTATIONS.contains(tokens[sub + 1])) {
            return Optional.of(new Match(
                    "git remote " + tokens[sub + 1],
                    "ByteQuay's own remote — don't rewire it"));
        }
        return Optional.empty();
    }

    private static Optional<Match> classifyGh(String[] tokens)
    {
        String area = tokens.length >= 2 ? tokens[1] : "";
        String action = tokens.length >= 3 ? tokens[2] : "";
        if (area.equals("pr") && GH_PR_MUTATIONS.contains(action)) {
            return Optional.of(new Match(
                    "gh pr " + action,
                    "ByteQuay's PR tools (`open_pr`, `merge_pr`, `update_pr_body`, "
                            + "`approve_pr`, `request_review`)"));
        }
        if (area.equals("release") && GH_RELEASE_MUTATIONS.contains(action)) {
            return Optional.of(new Match(
                    "gh release " + action,
                    "an explicit user action — releases aren't published from the agent"));
        }
        if (area.equals("api") && hasMutatingMethod(tokens)) {
            return Optional.of(new Match(
                    "gh api (mutating method)",
                    "ByteQuay's publish tools; raw GitHub API writes are blocked"));
        }
        return Optional.empty();
    }

    private static Optional<Match> classifyHttp(String[] tokens)
    {
        for (String token : tokens) {
            String host = token.toLowerCase(Locale.ROOT);
            if (host.contains("github.com") || host.contains("api.github.com")) {
                return Optional.of(new Match(
                        basename(tokens[0]) + " to GitHub",
                        "ByteQuay's tools or `gh api` (read-only) — don't hand-roll GitHub calls"));
            }
        }
        return Optional.empty();
    }

    /** True when a {@code gh api} call carries an explicit mutating method. */
    private static boolean hasMutatingMethod(String[] tokens)
    {
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            // `-X POST` / `--method POST` (value in the next token).
            if ((token.equals("-X") || token.equals("--method")) && i + 1 < tokens.length
                    && MUTATING_HTTP_METHODS.contains(tokens[i + 1].toUpperCase(Locale.ROOT))) {
                return true;
            }
            // Glued forms: `-XPOST`, `--method=POST`.
            String glued = null;
            if (token.startsWith("-X") && token.length() > 2) {
                glued = token.substring(2);
            }
            else if (token.startsWith("--method=")) {
                glued = token.substring("--method=".length());
            }
            if (glued != null && MUTATING_HTTP_METHODS.contains(glued.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Index of {@code git}'s subcommand token, skipping global options.
     * {@code -C <dir>} and {@code -c <name=value>} take an argument; other
     * leading {@code -flags} don't. Returns -1 when there's no subcommand.
     */
    private static int gitSubcommandIndex(String[] tokens)
    {
        int i = 1;
        while (i < tokens.length) {
            String token = tokens[i];
            if (token.equals("-C") || token.equals("-c")) {
                i += 2;
                continue;
            }
            if (token.startsWith("-")) {
                i += 1;
                continue;
            }
            return i;
        }
        return -1;
    }

    private static String basename(String token)
    {
        int slash = token.lastIndexOf('/');
        return slash >= 0 ? token.substring(slash + 1) : token;
    }
}
