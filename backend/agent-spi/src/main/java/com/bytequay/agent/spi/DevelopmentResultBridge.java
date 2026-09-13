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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;

/** Binds the fixed reporting and publication capability to an accepted ByteQuay turn. */
public record DevelopmentResultBridge(String turnId, String instruction, boolean learningSummaryAllowed)
{
    public static final String TOOL_NAME = "report_development_result";
    public static final String PUBLISH_TOOL_NAME = "create_draft_pull_request";
    public static final String INSTRUCTION = """
            Development result reporting is managed by ByteQuay for this turn. Before your final response,
            the root agent must call report_development_result with readyForDelivery=true only when the
            requested development work and appropriate verification are finished and no user decision is open.
            Report readyForDelivery=false before asking for an unresolved decision; do not commit or publish
            while that decision is open. Missing or invalid reports never authorize delivery. Subagents may
            contribute work but cannot report the root result. Do not supply task, turn, or agent identities.
            An optional message explains the outcome in at most 4000 characters. The user's accepted
            automation settings control delivery; this report cannot change them or select a stopping stage.
            If an explicit user restriction conflicts with enabled automation, report readyForDelivery=false
            and explain which enabled setting conflicts. A started task's automation cannot change, so suggest
            withdrawing the restriction or starting a new task with different settings. Never disregard requests
            such as "do not push" or "keep the PR draft" to satisfy an enabled automation setting.
            Completing a generated commit or draft-publication step does not end the remaining automation.
            Reporting readiness does not itself commit, push, create a PR, or authorize additional work.
            During ByteQuay's generated draft-publication step, call create_draft_pull_request with a title
            and body chosen from the actual change and the repository's conventions. Neither carries an
            attribution, co-authorship, or generated-with trailer. For that generated step, ByteQuay owns
            the push and GitHub write; use the tool without duplicating its writes through git or gh.
            The tool refuses calls outside ByteQuay's own publication request. During ordinary development
            turns, explicitly requested git or gh publishing follows the shared command authorization
            policy and does not require this tool. Its refusal does not block an authorized CLI operation.
            """;

    public DevelopmentResultBridge(String turnId, String instruction)
    {
        this(turnId, instruction, false);
    }

    public DevelopmentResultBridge
    {
        checkNotBlank(turnId, "turnId");
        checkNotBlank(instruction, "instruction");
    }
}
