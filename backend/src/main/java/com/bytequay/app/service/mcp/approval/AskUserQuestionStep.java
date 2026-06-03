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
 * AskUserQuestion is Claude asking the user something. The CLI
 * runs in non-interactive mode, so the built-in tool returns an
 * empty answer immediately. We render the question as a rich card
 * in our conversation view (the frontend special-cases this tool
 * name on the tool_call message), then deny here so Claude ends
 * the turn and waits — the user's reply arrives as the next chat
 * message. The deny message is deliberately blunt: without it
 * Claude tends to apologize about the failure and re-ask the same
 * question in plain prose, duplicating the card.
 */
@Component
@Order(200)
public class AskUserQuestionStep
        implements ApprovalStep
{
    /** The CLI built-in we special-case. The name matches what
     *  Claude Code passes as the target tool of the approval. */
    public static final String TARGET_TOOL = "AskUserQuestion";

    private static final String DENY_MESSAGE = ""
            + "SUCCESS — your question has been rendered to the user as "
            + "a rich card showing every option. STOP NOW: do not "
            + "write any further assistant text in this turn, do not "
            + "re-ask the question in prose, do not explain or "
            + "apologize, do not summarize the options. End the turn "
            + "immediately. The user will type their reply into the "
            + "chat input and you will see it as the next user "
            + "message.";

    private final McpResponses responses;

    public AskUserQuestionStep(McpResponses responses)
    {
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        if (!TARGET_TOOL.equals(ctx.toolName())) {
            return ApprovalStepResult.cont();
        }
        return ApprovalStepResult.resolve(
                responses.toolResponse(ctx.id(), responses.deny(DENY_MESSAGE)));
    }
}
