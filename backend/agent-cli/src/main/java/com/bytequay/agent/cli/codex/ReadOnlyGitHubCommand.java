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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a command only reads from GitHub, so it needs no approval card.
 *
 * <p>Reading changes nothing, so a {@code gh} listing, view, status, diff, check, search, or
 * uncustomized REST GET is answered as the sandbox would have been. Anything that could write or
 * transmit local input stays a card: other subcommands and HTTP methods, GraphQL, request fields,
 * bodies, headers or hosts, browser flags, shell expansion, and compound scripts.
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
    private static final ImmutableSetMultimap<String, String> REMOTE_LOCATOR_VERBS = ImmutableSetMultimap.<String, String>builder()
            .putAll("issue", "view")
            .putAll("pr", "view", "diff", "checks")
            .putAll("repo", "view")
            .putAll("gist", "view")
            .build();
    private static final Pattern DEFAULT_HOST_REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private ReadOnlyGitHubCommand() {}

    static boolean isReadOnly(String command)
    {
        List<String> words = CommandLine.simpleWords(command).orElse(ImmutableList.of());
        if (words.size() < 2 || !words.getFirst().equals("gh")) {
            return false;
        }
        if (words.stream().anyMatch(ReadOnlyGitHubCommand::opensBrowser)) {
            return false;
        }
        if (words.get(1).equals("api")) {
            return isReadOnlyApi(words);
        }
        if (selectsCustomHost(words)) {
            return false;
        }
        if (words.get(1).equals("status")) {
            return true;
        }
        if (words.size() < 3 || !READ_VERBS.containsEntry(words.get(1), words.get(2))) {
            return false;
        }
        return !words.get(1).equals("auth")
                || !words.get(2).equals("status")
                || (words.subList(3, words.size()).stream().noneMatch(ReadOnlyGitHubCommand::showsAuthToken)
                        && words.subList(3, words.size()).stream().noneMatch(ReadOnlyGitHubCommand::selectsAuthHostname));
    }

    private static boolean isReadOnlyApi(List<String> words)
    {
        String endpoint = null;
        for (int index = 2; index < words.size(); index++) {
            String word = words.get(index);
            switch (word) {
                case "-X", "--method" -> {
                    if (++index >= words.size() || !words.get(index).equalsIgnoreCase("GET")) {
                        return false;
                    }
                }
                case "-q", "--jq", "-p", "--preview", "-t", "--template" -> {
                    if (++index >= words.size()) {
                        return false;
                    }
                }
                case "-i", "--include", "--paginate", "--silent", "--slurp" -> {}
                case "-F", "--field", "-f", "--raw-field", "-H", "--header", "--input", "--hostname", "--cache", "--verbose" -> {
                    return false;
                }
                default -> {
                    if (word.startsWith("--method=")) {
                        if (!word.substring("--method=".length()).equalsIgnoreCase("GET")) {
                            return false;
                        }
                    }
                    else if (word.startsWith("-X") && word.length() > 2) {
                        if (!word.substring(2).equalsIgnoreCase("GET")) {
                            return false;
                        }
                    }
                    else if (startsWithAny(word, "--field=", "--raw-field=", "--header=", "--input=", "--hostname=", "--cache=", "-F", "-f", "-H")) {
                        return false;
                    }
                    else if (startsWithAny(word, "--jq=", "--preview=", "--template=")) {
                        // These options only shape the response.
                    }
                    else if (word.startsWith("-")) {
                        return false;
                    }
                    else if (endpoint != null) {
                        return false;
                    }
                    else {
                        endpoint = word;
                    }
                }
            }
        }
        if (endpoint == null) {
            return false;
        }
        String normalizedEndpoint = endpoint.toLowerCase(Locale.ENGLISH);
        return !normalizedEndpoint.equals("graphql")
                && !normalizedEndpoint.startsWith("graphql?")
                && !normalizedEndpoint.equals("/graphql")
                && !normalizedEndpoint.startsWith("/graphql?")
                && !normalizedEndpoint.startsWith("//")
                && !normalizedEndpoint.contains("://");
    }

    private static boolean opensBrowser(String word)
    {
        if (word.equals("--web") || word.startsWith("--web=")) {
            return true;
        }
        return word.startsWith("-") && !word.startsWith("--") && word.substring(1).contains("w");
    }

    private static boolean showsAuthToken(String word)
    {
        if (word.equals("--show-token") || word.startsWith("--show-token=")) {
            return true;
        }
        if (!word.startsWith("-") || word.startsWith("--")) {
            return false;
        }
        for (int index = 1; index < word.length(); index++) {
            if (word.charAt(index) == 't') {
                return true;
            }
            if (word.charAt(index) == 'h') {
                // The hostname shorthand consumes the rest of its word.
                return false;
            }
        }
        return false;
    }

    private static boolean selectsCustomHost(List<String> words)
    {
        for (int index = 2; index < words.size(); index++) {
            String word = words.get(index);
            if (word.equals("--hostname") || word.startsWith("--hostname=")) {
                return true;
            }
            if (word.equals("--repo") || word.equals("-R")) {
                if (++index >= words.size() || !isDefaultHostRepository(words.get(index))) {
                    return true;
                }
            }
            else if (word.startsWith("--repo=")) {
                if (!isDefaultHostRepository(word.substring("--repo=".length()))) {
                    return true;
                }
            }
            else if (word.startsWith("-R") && word.length() > 2) {
                if (!isDefaultHostRepository(word.substring(2))) {
                    return true;
                }
            }
            else if (word.startsWith("-") && !word.startsWith("--") && word.substring(1).contains("R")) {
                // pflag permits bundles such as -dRhost/owner/repo. Prompt rather than trying to
                // infer which preceding short options consume values.
                return true;
            }
        }
        return words.size() >= 3
                && REMOTE_LOCATOR_VERBS.containsEntry(words.get(1), words.get(2))
                && words.subList(3, words.size()).stream().anyMatch(ReadOnlyGitHubCommand::looksLikeCustomRemote);
    }

    private static boolean isDefaultHostRepository(String word)
    {
        return DEFAULT_HOST_REPOSITORY.matcher(word).matches();
    }

    private static boolean looksLikeCustomRemote(String word)
    {
        int firstSlash = word.indexOf('/');
        if (firstSlash < 0) {
            return false;
        }
        int colon = word.indexOf(':');
        return word.indexOf('/', firstSlash + 1) >= 0
                || (colon > 0 && colon < firstSlash);
    }

    private static boolean selectsAuthHostname(String word)
    {
        if (!word.startsWith("-") || word.startsWith("--")) {
            return false;
        }
        for (int index = 1; index < word.length(); index++) {
            if (word.charAt(index) == 'h') {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String value, String... prefixes)
    {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
