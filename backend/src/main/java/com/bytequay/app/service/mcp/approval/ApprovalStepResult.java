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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of one {@link ApprovalStep}. Either the step short-
 * circuits the chain with a fully-framed JSON-RPC response (the
 * step decided allow or deny on its own), or it defers to the next
 * step in the chain.
 */
public sealed interface ApprovalStepResult
        permits ApprovalStepResult.Continue, ApprovalStepResult.Resolve
{
    /** Returned by the step when it wants the chain to keep walking. */
    record Continue() implements ApprovalStepResult
    {
        public static final Continue INSTANCE = new Continue();
    }

    /** Returned by the step when it has decided the call's outcome.
     *  The {@code response} is the fully-framed JSON-RPC payload
     *  built via {@link com.bytequay.app.service.mcp.McpResponses}. */
    record Resolve(JsonNode response) implements ApprovalStepResult {}

    static ApprovalStepResult cont()
    {
        return Continue.INSTANCE;
    }

    static ApprovalStepResult resolve(JsonNode response)
    {
        return new Resolve(response);
    }
}
