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
package com.bytequay.app.service.agents;

import com.bytequay.app.domain.StageType;
import com.bytequay.app.service.skills.ByteQuayRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Keeps provider tool schemas bounded. A role is the ceiling; the current
 * stage chooses the smaller working set. Skills are resolved separately and
 * never make a forbidden role tool visible.
 */
@Component
public class ToolExposurePolicy
{
    public static final int MAX_ACTIVE_TOOLS = 32;

    /** Exact global-MCP catalogs used by typed V2 AgentTurn profiles. Plan
     * turns use their dedicated operation-scoped MCP instead. */
    public enum V2Profile
    {
        PLAN_PROTOCOL,
        TASK_BRAIN_READ_ONLY,
        LOCAL_DEVELOPMENT,
        REMOTE_DEVELOPMENT,
        CLEANUP
    }

    private static final Set<String> TRUNK = Set.of(
            "approval_prompt", "ask_user_question", "codegraph_explore", "explore_project",
            "recall_memory", "lookup_memory", "read_workspace_memory", "recall_thread",
            "list_prs", "read_pr", "read_issue", "read_task", "read_current_repository",
            "read_file", "sync_repo", "create_task", "propose_backlog_items",
            "list_terms", "lookup_term");

    private static final Set<String> BRAIN = Set.of(
            "approval_prompt", "ask_user_question", "codegraph_explore", "read_file",
            "recall_memory", "lookup_memory", "read_workspace_memory", "lookup_term",
            "count_operations", "read_commit_summary", "read_diff_summary",
            "check_test_coverage", "read_stage_metrics", "read_phase_history",
            "read_review_panel_findings", "read_remote_pr_status", "list_unresolved_comments",
            "record_plan", "read_plan_summary", "read_dev_report", "read_dev_conversation",
            "record_pr_comment", "record_review_verdict", "explore_project");

    private static final Set<String> COMPLETION_SUMMARY = Set.of(
            "codegraph_explore", "explore_project", "read_file",
            "recall_memory", "lookup_memory", "read_workspace_memory",
            "lookup_term", "count_operations", "read_commit_summary",
            "read_diff_summary", "check_test_coverage", "read_stage_metrics",
            "read_phase_history", "read_review_panel_findings",
            "read_remote_pr_status", "list_unresolved_comments",
            "read_plan_summary", "read_dev_report", "read_dev_conversation");

    private static final Set<String> TASK_CORE = Set.of(
            "approval_prompt", "ask_user_question", "codegraph_explore", "explore_project",
            "recall_memory", "lookup_memory", "read_workspace_memory", "lookup_term",
            "read_current_repository", "read_task", "read_plan_summary",
            "read_plan_conversation", "read_pr");

    private static final Set<String> DEVELOPMENT = union(TASK_CORE, Set.of(
            "run_checks", "record_iteration_summary", "record_dev_report",
            "read_dev_report", "read_dev_conversation", "record_pr_progress", "record_pr_description",
            "record_pr_commit", "record_pr_check", "record_local_review",
            "record_pr_comment", "resolve_pr_comment", "list_pr_review_threads"));

    private static final Set<String> REMOTE_DEVELOPMENT = union(TASK_CORE, Set.of(
            "read_remote_pr_status", "read_ci_log", "get_new_updated_ci_fixing_log", "run_checks", "validate",
            "record_iteration_summary", "record_dev_report", "read_dev_report",
            "record_pr_commit", "record_pr_check", "list_pr_review_threads",
            "record_round_reply", "resolve_review_comment"));

    private static final Set<String> CI_FIXING = union(TASK_CORE, Set.of(
            "read_remote_pr_status", "read_ci_log", "get_new_updated_ci_fixing_log", "run_checks", "validate",
            "record_iteration_summary", "record_dev_report", "read_dev_report",
            "record_pr_check", "list_pr_review_threads"));

    private static final Set<String> REVIEW = union(TASK_CORE, Set.of(
            "read_commit_summary", "read_diff_summary", "check_test_coverage",
            "list_unresolved_comments", "list_pr_review_threads", "record_round_reply",
            "record_pr_comment", "resolve_pr_comment", "record_review_verdict"));

    private static final Set<String> BRANCH_GUARD = union(TASK_CORE, Set.of(
            "read_remote_pr_status", "list_unresolved_comments", "list_pr_review_threads",
            "run_checks", "record_iteration_summary", "record_dev_report", "record_pr_check", "push"));

    private static final Set<String> V2_COMMON = Set.of(
            "approval_prompt", "ask_user_question", "codegraph_explore", "explore_project",
            "recall_memory", "lookup_memory", "read_workspace_memory", "lookup_term",
            "read_current_repository", "read_task", "read_plan_summary",
            "read_plan_conversation", "read_pr", "read_file");

    private static final Set<String> V2_TASK_BRAIN = union(V2_COMMON, Set.of(
            "count_operations", "read_commit_summary", "read_diff_summary",
            "check_test_coverage", "read_stage_metrics", "read_phase_history",
            "read_review_panel_findings", "read_remote_pr_status",
            "list_unresolved_comments", "read_dev_report", "read_dev_conversation"));

    private static final Set<String> V2_AUTOMATIC_TASK_BRAIN = Set.copyOf(
            V2_TASK_BRAIN.stream()
                    .filter(tool -> !Set.of(
                            "approval_prompt", "ask_user_question").contains(tool))
                    .toList());

    private static final Set<String> V2_LOCAL_DEVELOPMENT = union(V2_COMMON, Set.of(
            "run_checks", "read_dev_report", "read_dev_conversation",
            "list_unresolved_comments", "list_pr_review_threads",
            // The Turn's result. Without it in the catalog the tool never
            // reaches tools/list and every Development Turn fails unreported.
            "record_development_result"));

    private static final Set<String> V2_REMOTE_DEVELOPMENT = union(V2_COMMON, Set.of(
            "run_checks", "read_remote_pr_status", "read_ci_log",
            "get_new_updated_ci_fixing_log", "read_dev_report", "read_dev_conversation",
            "list_unresolved_comments", "list_pr_review_threads"));

    public Set<String> activeTools(ByteQuayRole role, StageType stageType)
    {
        Set<String> selected = switch (role) {
            case TRUNK -> TRUNK;
            case BRAIN -> BRAIN;
            case REVIEWER -> REVIEW;
            case TASK -> taskTools(stageType);
        };
        if (selected.size() > MAX_ACTIVE_TOOLS) {
            throw new IllegalStateException("tool context exceeds " + MAX_ACTIVE_TOOLS
                    + " entries for " + role + "/" + stageType + ": " + selected.size());
        }
        return selected;
    }

    /** Read-only Brain subset for automatic Task completion enrichment. */
    public Set<String> completionSummaryTools()
    {
        return COMPLETION_SUMMARY;
    }

    /** Finite automatic verdicts cannot suspend an owning remote episode. */
    public Set<String> automaticTaskBrainReviewTools()
    {
        return V2_AUTOMATIC_TASK_BRAIN;
    }

    /**
     * V2 AgentTurns complete only through their exact owner result. These
     * catalogs therefore expose observation, typed user-wait, and local check
     * helpers, never a legacy lifecycle/artifact mutation tool.
     */
    public Set<String> v2Tools(V2Profile profile)
    {
        Set<String> selected = switch (profile) {
            case PLAN_PROTOCOL, CLEANUP -> Set.of();
            case TASK_BRAIN_READ_ONLY -> V2_TASK_BRAIN;
            case LOCAL_DEVELOPMENT -> V2_LOCAL_DEVELOPMENT;
            case REMOTE_DEVELOPMENT -> V2_REMOTE_DEVELOPMENT;
        };
        if (selected.size() > MAX_ACTIVE_TOOLS) {
            throw new IllegalStateException(
                    "V2 tool context exceeds " + MAX_ACTIVE_TOOLS
                            + " entries for " + profile + ": " + selected.size());
        }
        return selected;
    }

    private static Set<String> taskTools(StageType stageType)
    {
        if (stageType == null) {
            return DEVELOPMENT;
        }
        return switch (stageType) {
            case DEVELOPMENT_STAGE -> DEVELOPMENT;
            case REMOTE_DEVELOPMENT_STAGE, REVIEW_MONITOR_STAGE -> REMOTE_DEVELOPMENT;
            case CI_FIXING_STAGE -> CI_FIXING;
            case REVIEW_STAGE, REVIEW_ROUND_STAGE, PLAN_STAGE -> REVIEW;
            case BRANCH_GUARD_STAGE -> BRANCH_GUARD;
            case CLEANUP_STAGE -> Set.of();
        };
    }

    private static Set<String> union(Set<String> first, Set<String> second)
    {
        LinkedHashSet<String> out = new LinkedHashSet<>(first);
        out.addAll(second);
        return Set.copyOf(out);
    }
}
