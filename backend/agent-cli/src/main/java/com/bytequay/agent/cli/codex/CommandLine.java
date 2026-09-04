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

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** The words of a command line when it is one simple command, looking through a {@code sh -lc} wrapper. */
final class CommandLine
{
    private static final Pattern SHELL_WRAPPER = Pattern.compile("^(?:/bin/)?(?:z|ba)?sh[ \\t]+-l?c[ \\t]+(.+)$", Pattern.DOTALL);
    private static final String SHELL_OPERATORS = "|&;<>`(){}";
    private static final String UNQUOTED_EXPANSIONS = "$*?[~^#!";

    private CommandLine() {}

    /** Empty when the command is compound, so no leading words describe all of it. */
    static Optional<List<String>> simpleWords(String command)
    {
        requireNonNull(command, "command is null");
        Optional<String> unwrapped = unwrap(command.strip());
        if (unwrapped.isEmpty()) {
            return Optional.empty();
        }
        String script = unwrapped.orElseThrow();
        ImmutableList.Builder<String> words = ImmutableList.builder();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        boolean wordStarted = false;

        for (int index = 0; index < script.length(); index++) {
            char value = script.charAt(index);
            if (value == '\n' || value == '\r') {
                return Optional.empty();
            }
            if (escaped) {
                word.append(value);
                wordStarted = true;
                escaped = false;
                continue;
            }
            if (quote == '\'') {
                if (value == '\'') {
                    quote = 0;
                }
                else {
                    word.append(value);
                }
                wordStarted = true;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                wordStarted = true;
                continue;
            }
            if (quote == '"') {
                if (value == '"') {
                    quote = 0;
                }
                else if (value == '$' || value == '`') {
                    return Optional.empty();
                }
                else {
                    word.append(value);
                }
                wordStarted = true;
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                wordStarted = true;
                continue;
            }
            if ((value == '=' && !wordStarted)
                    || SHELL_OPERATORS.indexOf(value) >= 0
                    || UNQUOTED_EXPANSIONS.indexOf(value) >= 0) {
                return Optional.empty();
            }
            if (value == ' ' || value == '\t') {
                if (wordStarted) {
                    words.add(word.toString());
                    word.setLength(0);
                    wordStarted = false;
                }
                continue;
            }
            if (Character.isWhitespace(value) || Character.isISOControl(value)) {
                return Optional.empty();
            }
            word.append(value);
            wordStarted = true;
        }
        if (escaped || quote != 0) {
            return Optional.empty();
        }
        if (wordStarted) {
            words.add(word.toString());
        }
        return Optional.of(words.build());
    }

    private static Optional<String> unwrap(String command)
    {
        Matcher wrapper = SHELL_WRAPPER.matcher(command);
        if (!wrapper.matches()) {
            return Optional.of(command);
        }
        String script = wrapper.group(1).strip();
        if (script.length() < 2) {
            return Optional.empty();
        }
        char quote = script.charAt(0);
        if ((quote != '"' && quote != '\'') || script.charAt(script.length() - 1) != quote) {
            return Optional.empty();
        }
        String inner = script.substring(1, script.length() - 1);
        if (quote == '"' && (inner.indexOf('\\') >= 0
                || inner.indexOf('$') >= 0
                || inner.indexOf('`') >= 0
                || inner.indexOf('"') >= 0)) {
            return Optional.empty();
        }
        if (quote == '\'' && inner.indexOf('\'') >= 0) {
            return Optional.empty();
        }
        return Optional.of(inner);
    }
}
