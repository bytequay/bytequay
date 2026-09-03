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
package com.bytequay.agent.cli.codex;

import com.google.common.collect.ImmutableSetMultimap;

import java.util.List;

/**
 * Whether a command only reads from GitHub, so it needs no approval card.
 *
 * <p>Reading changes nothing, so a {@code gh} listing, view, status, diff, check or search is
 * answered as the sandbox would have been. Anything that could write stays a card: every other
 * {@code gh} subcommand, {@code gh api} (a GET by default, a POST with a field), a {@code --web}
 * flag that opens the browser, and a compound script, whose other commands are not looked at.
 */
final class ReadOnlyGitHubCommand
{
    private static final ImmutableSetMultimap<String, String> READ_VERBS = ImmutableSetMultimap.<String, String>builder()
            .putAll("issue", "list", "view", "status")
            .putAll("pr", "list", "view", "status", "diff", "checks")
            .putAll("repo", "list", "view")
            .putAll("run", "list", "view")
            .putAll("workflow", "list", "view")
            .putAll("release", "list", "view")
            .putAll("label", "list")
            .putAll("gist", "list", "view")
            .putAll("project", "list", "view")
            .putAll("ruleset", "list", "view")
            .putAll("cache", "list")
            .putAll("search", "code", "commits", "issues", "prs", "repos")
            .putAll("auth", "status")
            .build();

    private ReadOnlyGitHubCommand() {}

    static boolean isReadOnly(String command)
    {
        List<String> words = CommandLine.simpleWords(command).orElse(List.of());
        if (words.size() < 2 || !words.getFirst().equals("gh")) {
            return false;
        }
        if (words.stream().anyMatch(word -> word.equals("--web") || word.equals("-w"))) {
            return false;
        }
        if (words.get(1).equals("status")) {
            return true;
        }
        return words.size() >= 3 && READ_VERBS.containsEntry(words.get(1), words.get(2));
    }
}
