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

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** The words of a command line when it is one simple command, looking through a {@code sh -lc} wrapper. */
final class CommandLine
{
    private static final Pattern SHELL_WRAPPER = Pattern.compile("^(?:/bin/)?(?:z|ba)?sh\\s+-l?c\\s+(.+)$", Pattern.DOTALL);
    // ponytail: an operator makes the script compound and nothing here summarizes it; a shell parser if quoting ever matters
    private static final Pattern SHELL_OPERATOR = Pattern.compile("[|&;<>`]|\\$\\(");

    private CommandLine() {}

    /** Empty when the command is compound, so no leading words describe all of it. */
    static Optional<List<String>> simpleWords(String command)
    {
        requireNonNull(command, "command is null");
        String script = unwrap(command.strip());
        if (SHELL_OPERATOR.matcher(script).find()) {
            return Optional.empty();
        }
        return Optional.of(Splitter.on(CharMatcher.whitespace()).omitEmptyStrings().splitToList(script));
    }

    private static String unwrap(String command)
    {
        Matcher wrapper = SHELL_WRAPPER.matcher(command);
        if (!wrapper.matches()) {
            return command;
        }
        String script = wrapper.group(1).strip();
        if (script.length() < 2) {
            return script;
        }
        char quote = script.charAt(0);
        if ((quote == '"' || quote == '\'') && script.charAt(script.length() - 1) == quote) {
            return script.substring(1, script.length() - 1);
        }
        return script;
    }
}
