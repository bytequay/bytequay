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
package com.bytequay.agent.cli.process;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** The fixed shell-command rule of a cherry-pick task, the same for every agent: only the expected fixup may change Git history. */
public final class CherryPickCommands
{
    private static final Pattern FIXUP = Pattern.compile(
            "^git\\s+commit\\s+(?:(?:--allow-empty\\s+--fixup=)|(?:--fixup=))([0-9a-f]{40}|[0-9a-f]{64})(?:\\s+--allow-empty)?$");
    private static final Pattern AMEND = Pattern.compile("^git\\s+commit\\s+--amend\\s+--no-edit$");
    private static final Pattern HISTORY_MUTATION = Pattern.compile(
            "(?m)\\bgit\\b[^\\n;&|]*\\b(?:commit|cherry-pick|rebase|reset|merge|switch|checkout|branch|tag|push|fetch|pull|update-ref|restore)\\b");
    // Codex reports a shell-wrapped command as the shell invocation, so its script is what the rule reads.
    private static final Pattern SHELL_WRAPPER = Pattern.compile("^(?:\\S*/)?(?:bash|zsh|sh)\\s+-l?c\\s+(.+)$", Pattern.DOTALL);

    private CherryPickCommands() {}

    public static boolean allows(String command)
    {
        String script = script(requireNonNull(command, "command is null").strip());
        return FIXUP.matcher(script).matches()
                || AMEND.matcher(script).matches()
                || (!script.isBlank() && !HISTORY_MUTATION.matcher(script).find());
    }

    private static String script(String command)
    {
        Matcher shell = SHELL_WRAPPER.matcher(command);
        if (!shell.matches()) {
            return command;
        }
        String script = shell.group(1).strip();
        char quote = script.charAt(0);
        if (script.length() >= 2 && (quote == '\'' || quote == '"') && script.charAt(script.length() - 1) == quote) {
            script = script.substring(1, script.length() - 1);
        }
        return script.strip();
    }
}
