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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * The fixed review capability a private review session exposes to its root agent.
 *
 * <p>The tools are ByteQuay's local reporting and context operations; the instruction is the
 * managed review policy for the provider's system or developer channel. Both are delivered on
 * every session open, initial or resumed, and never through a repository file. A local review
 * without a report uses the same policy with no reporting tools.
 */
public record ReviewBridge(List<ReviewTool> tools, String instruction)
{
    /**
     * Remote writes and credential reads a review session refuses before any approval card. The
     * review token exposes no publisher. This catches visible native commands at both providers'
     * approval boundary; it does not inspect arbitrary programs invoked by the shell.
     */
    private static final Pattern REMOTE_WRITE = Pattern.compile(
            "(?i)(?:^|[\\s;&|(`])(?:"
                    + "git\\s+(?:[^\\s;&|]+\\s+)*?(?:push|send-email|request-pull|credential(?:-\\w+)?)\\b"
                    + "|gh\\s+(?:[^\\s;&|]+\\s+)*?(?:"
                    + "pr\\s+(?:comment|review|merge|close|reopen|edit|create|ready|lock|unlock)"
                    + "|issue\\s+(?:comment|close|create|edit|reopen|lock|unlock|delete)"
                    + "|api\\s+(?:[^\\s;&|]+\\s+)*?(?:-X|--method|-f|--field|-F|--raw-field|--input)\\b"
                    + "|auth\\s+(?:token|login|logout|refresh|setup-git)"
                    + "|release|repo\\s+(?:create|delete|edit|fork|sync)|secret|ssh-key|gpg-key"
                    + "|label\\s+(?:create|delete|edit)|workflow\\s+(?:run|enable|disable))\\b"
                    + "|(?:cat|less|more|head|tail|cp|open|source|\\.)\\s+(?:[-\\w]+\\s+)*(?:~|\\$HOME)?/?"
                    + "(?:\\.config/gh|\\.git-credentials|\\.netrc|\\.ssh/|\\.codex/auth|\\.claude/)"
                    + "|security\\s+(?:[-\\w]+\\s+)*find-(?:generic|internet)-password"
                    + ")");

    private static final String SHELL_WORD = "(?:\"[^\"]*\"|'[^']*'|[^\\s;&|]+)";
    private static final String GIT_COMMAND = "(?:^|[\\s;&|(`'\"])(?:[^\\s;&|`'\"]*/)?git['\"]?\\s+"
            + "(?:(?:-[Cc]\\s+" + SHELL_WORD + "|--(?:git-dir|work-tree|namespace|config-env)(?:=|\\s+)" + SHELL_WORD + "|--?[-\\w]+)\\s+)*";
    private static final String COMMAND_END = "(?=$|[;&|)`'\"\\r\\n])";
    // Require an explicit path separator for named paths: `reset name` can also move HEAD to a revision.
    private static final String RESET_LOCAL_ARGUMENTS = "(?:\\s+(?:-q|--quiet))*"
            + "(?:(?:\\s+--hard)?(?:\\s+HEAD)?\\s*" + COMMAND_END
            + "|\\s+(?:" + SHELL_WORD + "\\s+)?--(?:\\s+" + SHELL_WORD + ")+\\s*" + COMMAND_END + ")";
    private static final String SYMBOLIC_REF_READ_ARGUMENTS = "(?:\\s+(?:-q|--quiet|--short|--recurse|--no-recurse))*"
            + "\\s+(?!-)" + SHELL_WORD + "\\s*" + COMMAND_END;
    // This checks visible native commands; invoked scripts and aliases still depend on the provider sandbox.
    private static final Pattern HISTORY_WRITE = Pattern.compile(GIT_COMMAND + "(?:"
            + "(?:commit|commit-tree|update-ref|merge|rebase|cherry-pick|revert|am|switch|pull)(?![-\\w])"
            + "|reset(?![-\\w])(?!" + RESET_LOCAL_ARGUMENTS + ")"
            + "|symbolic-ref(?![-\\w])(?!" + SYMBOLIC_REF_READ_ARGUMENTS + ")"
            + "|branch\\s+(?!--(?:list|show-current|all|remotes|contains|merged|no-merged|points-at|format|sort)\\b|-[alrv]+(?:\\s|$))[^\\s;&|]"
            + "|checkout\\s+(?:" + SHELL_WORD + "\\s+)*?(?:-[bB]\\S*|--orphan\\b)"
            + ")");

    public ReviewBridge
    {
        tools = ImmutableList.copyOf(tools);
        checkNotBlank(instruction, "instruction");
        checkArgument(tools.stream().map(ReviewTool::name).distinct().count() == tools.size(), "tool names are not unique");
    }

    public Optional<ReviewTool> tool(String name)
    {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    /**
     * Why a native command is refused for a review session, or empty when it may be considered.
     * A denial here is final: it never becomes an approval card, and no prompt text or PR
     * instruction can lift it.
     */
    public static Optional<String> deniedReason(String command)
    {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        if (HISTORY_WRITE.matcher(command).find()) {
            return Optional.of("Private review experiments must remain uncommitted; leave local review before committing or changing Git history");
        }
        return REMOTE_WRITE.matcher(command).find()
                ? Optional.of("Review sessions cannot push, write to GitHub, or read credentials; publication is a ByteQuay user action")
                : Optional.empty();
    }
}
