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

import java.util.Set;

/**
 * Conservatively decides whether a shell command is <em>provably</em>
 * read-only — safe to auto-approve so codebase exploration doesn't spin
 * on a prompt. The bar is deliberately high: anything that could write,
 * substitute, chain into a mutation, or hit the network falls through to
 * a human prompt. A false "needs approval" is mild friction; a false
 * auto-approve is a foot-gun, so every ambiguous case returns false.
 *
 * <p>Allowed: a pipeline ({@code |}) of allow-listed read commands.
 * {@code find} is allowed including {@code -exec <readcmd>} (so
 * {@code find … -exec grep …} works) but never {@code -delete} /
 * {@code -fprint*} / an {@code -exec} of a non-read command. {@code git}
 * is allowed only for read subcommands.
 *
 * <p>Rejected outright: redirects ({@code > < >>}), command substitution
 * ({@code $(…)}, backticks), sequencing / background ({@code ; & && ||}),
 * newlines, and any command not on the read-only allow-list (so
 * {@code rm}, {@code curl}, {@code sed -i}, {@code xargs}, {@code git
 * push}, … all prompt).
 */
public final class ReadOnlyShellClassifier
{
    private ReadOnlyShellClassifier() {}

    private static final Set<String> READ_ONLY_COMMANDS = ImmutableSet.of(
            "grep", "egrep", "fgrep", "rg", "ls", "cat", "head", "tail", "wc",
            "sort", "uniq", "nl", "cut", "comm", "tac", "column",
            "basename", "dirname", "realpath", "pwd", "tree", "stat", "file",
            "du", "which", "printenv", "date", "echo");

    /** Git subcommands that only read. Mutating ones (push, commit,
     *  checkout, branch -D, tag -d, remote add, config set, …) are absent
     *  on purpose, so they prompt. {@code fetch} updates local
     *  remote-tracking refs and touches the network, but never the shared
     *  remote or the worktree, so it's allow-listed alongside the rest —
     *  {@link DenyRemoteGitStep} still hard-denies anything that pushes or
     *  mutates GitHub-side state regardless of this list. */
    private static final Set<String> GIT_READ_SUBCOMMANDS = ImmutableSet.of(
            "status", "diff", "log", "show", "ls-files", "ls-tree",
            "cat-file", "rev-parse", "rev-list", "blame", "describe", "shortlog", "fetch");

    /** {@code find} primaries that run a command — only allowed when the
     *  command they run is itself read-only. */
    private static final Set<String> FIND_EXEC_PRIMARIES = ImmutableSet.of(
            "-exec", "-execdir", "-ok", "-okdir");

    /** {@code find} primaries that write / delete — never allowed. */
    private static final Set<String> FIND_WRITE_PRIMARIES = ImmutableSet.of(
            "-delete", "-fprint", "-fprintf", "-fls");

    /** True only when {@code command} is provably a read-only shell. */
    public static boolean isReadOnly(String command)
    {
        if (command == null) {
            return false;
        }
        String trimmed = command.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        // Neutralise backslash-escaped chars (e.g. find's `\;`) so they
        // don't trip the operator scan, while real operators remain.
        String scan = trimmed.replaceAll("\\\\.", "  ");
        // Benign output sinks write nothing real: redirecting stdout/stderr to
        // /dev/null (e.g. `find … 2>/dev/null`) or merging stderr into stdout
        // (`2>&1`). Strip these before the redirect/`&` scan so a read command
        // that suppresses noise stays auto-approvable. A redirect to any other
        // target keeps its `>`/`<` and still falls through to a prompt.
        scan = scan.replaceAll("(?:\\d*|&)>\\s*/dev/null", "  ").replace("2>&1", "  ");
        if (scan.indexOf('>') >= 0 || scan.indexOf('<') >= 0 || scan.indexOf('`') >= 0
                || scan.contains("$(") || scan.indexOf(';') >= 0 || scan.indexOf('&') >= 0
                || scan.indexOf('\n') >= 0 || scan.indexOf('\r') >= 0) {
            return false;
        }
        // A pipeline of read commands is fine; every segment must read.
        // (An empty segment — from `||` or a leading/trailing `|` — fails.)
        for (String segment : trimmed.split("\\|")) {
            if (!isReadOnlySegment(segment.strip())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReadOnlySegment(String segment)
    {
        if (segment.isEmpty()) {
            return false;
        }
        String[] tokens = segment.split("\\s+");
        String cmd = basename(tokens[0]);
        if (cmd.equals("git")) {
            return tokens.length >= 2 && GIT_READ_SUBCOMMANDS.contains(tokens[1]);
        }
        if (cmd.equals("find")) {
            return isReadOnlyFind(tokens);
        }
        return READ_ONLY_COMMANDS.contains(cmd);
    }

    private static boolean isReadOnlyFind(String[] tokens)
    {
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (FIND_WRITE_PRIMARIES.contains(token)) {
                return false;
            }
            if (FIND_EXEC_PRIMARIES.contains(token)) {
                if (i + 1 >= tokens.length || !READ_ONLY_COMMANDS.contains(basename(tokens[i + 1]))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String basename(String token)
    {
        int slash = token.lastIndexOf('/');
        return slash >= 0 ? token.substring(slash + 1) : token;
    }
}
