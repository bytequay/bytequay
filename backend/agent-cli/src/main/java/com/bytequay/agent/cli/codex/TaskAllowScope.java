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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * The words an allow-for-this-task covers, derived from a command line.
 *
 * <p>A command's leading words name what it does ("gh issue list") while the options and
 * operands after them are the particular case, so an allow for the task covers every later
 * command whose first three words are the same, none of them an option. A command with fewer
 * such words ("git status", "sed -n 1,220p") or a compound script is covered only by an
 * identical command line, because a shorter prefix ("gh api") would also cover commands that
 * do something else. The script inside a {@code sh -lc} wrapper is what is summarized.
 */
final class TaskAllowScope
{
    private static final Pattern SHELL_WRAPPER = Pattern.compile("^(?:/bin/)?(?:z|ba)?sh\\s+-l?c\\s+(.+)$", Pattern.DOTALL);
    // ponytail: an operator makes the script compound and it is then covered only verbatim; a shell parser if quoting ever matters
    private static final Pattern SHELL_OPERATOR = Pattern.compile("[|&;<>`]|\\$\\(");
    private static final int LEADING_WORDS = 3;

    private TaskAllowScope() {}

    static String of(String command)
    {
        requireNonNull(command, "command is null");
        String script = unwrap(command.strip());
        if (SHELL_OPERATOR.matcher(script).find()) {
            return command;
        }
        List<String> words = Splitter.on(CharMatcher.whitespace()).omitEmptyStrings().splitToList(script);
        int leading = 0;
        while (leading < words.size() && leading < LEADING_WORDS && !words.get(leading).startsWith("-")) {
            leading++;
        }
        return leading < LEADING_WORDS ? command : String.join(" ", words.subList(0, LEADING_WORDS));
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
