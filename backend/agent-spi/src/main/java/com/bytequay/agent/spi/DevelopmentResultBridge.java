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

import java.util.Optional;
import java.util.function.Function;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Binds the fixed reporting and publication capability to an accepted ByteQuay turn. */
public record DevelopmentResultBridge(String turnId, String instruction, boolean learningSummaryAllowed, Optional<Function<String, String>> branchRenamer)
{
    public static final String TOOL_NAME = "report_development_result";
    public static final String PUBLISH_TOOL_NAME = "create_draft_pull_request";
    public static final String RENAME_BRANCH_TOOL_NAME = "rename_task_branch";
    public static final String BRANCH_INSTRUCTION = """

            The task's initial branch name is provisional because it was chosen before investigation.
            Once the actual change is clear, call rename_task_branch before your first turn's final
            response and BEFORE any first git push or PR creation, even when publishing in this turn.
            Branch naming is available only during this first turn. If no concrete change is known by
            its end, keep the initial name. Respect an explicit branch name from the user.
            Supply only a concise English action-and-component suffix describing the actual diff, using
            lowercase ASCII words separated by hyphens, at most 64 characters. Preserve meaningful terms;
            for example convert-parquet-time-millis-to-micro, not find-and-fix-issue. Do not summarize
            the user's generic request or include a username, slash, random ID, or hash. ByteQuay preserves
            the username/bytequay/ prefix and updates task ownership. Use this tool instead of git branch -m.
            The tool keeps already published or imported PR branches unchanged. On a name collision,
            choose another accurate suffix; never overwrite a branch. Use the returned full branch name
            for subsequent Git and GitHub commands. Renaming locally does not authorize publication.
            """;
    public static final String INSTRUCTION = """
            Development result reporting is managed by ByteQuay for this turn. Before your final response,
            the root agent must call report_development_result with readyForDelivery=true only when the
            requested development work and appropriate verification are finished and no user decision is open.
            Report readyForDelivery=false before asking for an unresolved decision; do not perform the action
            awaiting that decision. A request to review before pushing or updating a PR holds publication,
            not authorized local commits of finished work. The reviewer shares this task's working copy and
            can review uncommitted changes; a review request does not require a commit. Follow Auto commit
            and explicit user restrictions independently. Keep readyForDelivery=false while publication
            is on hold so automatic publication cannot start. Missing or invalid reports never authorize delivery. Subagents may
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
            Keep the task branch name fixed after the first turn, even when it is still unpublished.
            Do not rename it in later turns through Git commands or other tools.
            """;

    public DevelopmentResultBridge
    {
        checkText(turnId, "turnId");
        checkText(instruction, "instruction");
        requireNonNull(branchRenamer, "branchRenamer is null");
    }
}
