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

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The words an allow-for-this-task covers, derived from a command line.
 *
 * <p>A command's leading words name what it does ("gh issue list") while the options and
 * operands after them are the particular case, so an allow for the task covers every later
 * command whose first three words are the same, none of them an option. A command with fewer
 * such words ("git status", "sed -n 1,220p") or a compound script is covered only by an
 * identical command line, because a shorter prefix ("gh api") would also cover commands that
 * do something else.
 */
final class TaskAllowScope
{
    private static final int LEADING_WORDS = 3;

    private TaskAllowScope() {}

    static String of(String command)
    {
        requireNonNull(command, "command is null");
        List<String> words = CommandLine.simpleWords(command).orElse(List.of());
        int leading = 0;
        while (leading < words.size() && leading < LEADING_WORDS && !words.get(leading).startsWith("-")) {
            leading++;
        }
        return leading < LEADING_WORDS ? command : String.join(" ", words.subList(0, LEADING_WORDS));
    }
}
