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

import com.bytequay.app.service.codegraph.CodeGraphFirstRuntime;
import com.bytequay.app.service.codegraph.CodeGraphFirstSearchClassifier;
import com.bytequay.app.service.codegraph.CodeGraphFirstSearchClassifier.Suggestion;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Redirect broad Claude CLI discovery to the checkout's CodeGraph first. */
@Component
@Order(290)
public class CodeGraphFirstStep
        implements ApprovalStep
{
    private final McpResponses responses;

    public CodeGraphFirstStep(McpResponses responses)
    {
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext context)
    {
        if (context.agentKey() == null || context.agentKey().isBlank()) {
            return ApprovalStepResult.cont();
        }
        if (!CodeGraphFirstSearchClassifier.isBroadDiscovery(context)) {
            return ApprovalStepResult.cont();
        }
        if (context.isShellTool() && !ReadOnlyShellClassifier.isReadOnly(context.shellCommand())) {
            // This policy is a search preference, not a mutation/security
            // gate. Mixed or ambiguous shell commands continue to the real
            // safety steps instead of receiving an irrelevant graph message.
            return ApprovalStepResult.cont();
        }
        if (!CodeGraphFirstRuntime.shouldRedirect(context.threadId(), context.agentKey())) {
            return ApprovalStepResult.cont();
        }
        return ApprovalStepResult.resolve(
                responses.toolResponse(context.id(), responses.deny(redirectMessage(context))));
    }

    private static String redirectMessage(ApprovalContext context)
    {
        Suggestion suggestion = CodeGraphFirstSearchClassifier.suggestion(context);
        String mode = suggestion.symbol() ? ",\"mode\":\"symbol\"" : "";
        String call = "mcp__bytequay__codegraph_explore({\"query\":"
                + jsonString(suggestion.query()) + mode + "})";
        return """
                Blocked by ByteQuay's CodeGraph-first exploration policy.
                This looks like broad repository discovery. Call this tailored tool request first:

                %s

                Then use native search for exact literal checks or completeness verification.
                Correct invalid CodeGraph arguments and retry. A real CodeGraph attempt, including
                an unavailable/indexing failure, unlocks native search; ignored redirects fail open
                after two rejections.
                """.formatted(call);
    }

    private static String jsonString(String value)
    {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"";
    }
}
