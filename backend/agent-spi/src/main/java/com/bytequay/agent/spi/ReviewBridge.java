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
 * The fixed review capability a review-owned task's provider session exposes to its root agent.
 *
 * <p>The tools are ByteQuay's local reporting and context operations; the instruction is the
 * managed review policy for the provider's system or developer channel. Both are delivered on
 * every session open, initial or resumed, and never through a repository file.
 */
public record ReviewBridge(List<ReviewTool> tools, String instruction)
{
    /**
     * Remote writes and credential reads a review session refuses before any approval card. The
     * review token exposes no publisher, so the only remaining route to GitHub is a native shell
     * command, and that route is closed here for both provider lanes.
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

    public ReviewBridge
    {
        tools = ImmutableList.copyOf(tools);
        checkNotBlank(instruction, "instruction");
        checkArgument(!tools.isEmpty(), "tools is empty");
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
        return REMOTE_WRITE.matcher(command).find()
                ? Optional.of("Review sessions cannot push, write to GitHub, or read credentials; publication is a ByteQuay user action")
                : Optional.empty();
    }
}
