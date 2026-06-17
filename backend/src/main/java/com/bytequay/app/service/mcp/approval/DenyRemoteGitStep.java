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

import com.bytequay.app.service.mcp.McpResponses;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Hard-deny a raw shell call that would push code or mutate PR / release /
 * review state on GitHub directly (see {@link RemoteGitClassifier}), and
 * tell the agent which ByteQuay tool to use instead. This is the
 * enforcement half of "the agent publishes through our tools, not raw
 * git/gh": the {@code push} / {@code open_pr} / {@code merge_pr} / …
 * tools park a proposal the user approves, so a direct {@code git push} or
 * {@code gh pr create} would bypass the one gate that keeps anything from
 * reaching GitHub without an explicit user action.
 *
 * <p>Ordered after {@link ParkedToolStep} (@Order 305) — so the agent's
 * own PARKED publish tools still auto-allow — and before
 * {@link ReadOnlyShellStep} (@Order 310): a remote mutation is never
 * read-only, so read-only classification would let it fall through to the
 * user prompt; denying here turns it into immediate, self-explaining
 * feedback instead of a 2-minute timeout. Read / local shell work
 * ({@code git status}, {@code gh pr view}, a GET) isn't flagged, so it
 * continues down the chain untouched.
 */
@Component
@Order(308)
public class DenyRemoteGitStep
        implements ApprovalStep
{
    private final McpResponses responses;

    public DenyRemoteGitStep(McpResponses responses)
    {
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        if (!ctx.isShellTool()) {
            return ApprovalStepResult.cont();
        }
        return RemoteGitClassifier.findRemoteMutation(ctx.shellCommand())
                .map(match -> ApprovalStepResult.resolve(
                        responses.toolResponse(ctx.id(), responses.deny(denyMessage(match)))))
                .orElseGet(ApprovalStepResult::cont);
    }

    private static String denyMessage(RemoteGitClassifier.Match match)
    {
        return "`" + match.blocked() + "` is blocked. ByteQuay publishes to GitHub through "
                + "its own tools, not raw git/gh — use " + match.useInstead() + ". These tools "
                + "park a proposal the user approves; direct pushes and GitHub API writes never "
                + "reach the remote, so this call would be a dead end.";
    }
}
