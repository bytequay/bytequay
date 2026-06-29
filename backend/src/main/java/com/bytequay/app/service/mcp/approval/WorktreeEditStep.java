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

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Per-task "accept edits in worktree" auto-approval. When the thread's
 * active Task has the toggle on, a file-edit tool whose target path is
 * inside that Task's worktree is allowed without a prompt.
 *
 * <p>Deliberately narrow so the app's "nothing reaches GitHub without an
 * explicit action" invariant holds:
 * <ul>
 *   <li>Only the file-edit tools ({@code Edit}, {@code Write},
 *       {@code MultiEdit}, {@code NotebookEdit}) — never {@code Bash} /
 *       {@code run_shell}, which could {@code git push} or hit the
 *       network.</li>
 *   <li>Only when the resolved target is inside the worktree. A write
 *       outside the sandbox falls through to the normal prompt.</li>
 * </ul>
 *
 * <p>Ordered after {@link AutoGatingStep} (300) and before
 * {@link BudgetStep} (400): read-only tools are already handled, and an
 * accept-edits allow shouldn't consume a separate "Allow next N" budget.
 */
@Component
@Order(350)
public class WorktreeEditStep
        implements ApprovalStep
{
    private static final Set<String> FILE_EDIT_TOOLS =
            Set.of("Edit", "Write", "MultiEdit", "NotebookEdit");

    /** Input keys the file-edit tools carry their target path under. */
    private static final String[] PATH_KEYS = {"file_path", "notebook_path", "path"};

    private final TaskStore taskStore;
    private final McpResponses responses;

    public WorktreeEditStep(TaskStore taskStore, McpResponses responses)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        if (!FILE_EDIT_TOOLS.contains(ctx.toolName())) {
            return ApprovalStepResult.cont();
        }
        // Resolve the task from the turn's stamped task_id — NOT a thread-level
        // "active task" guess, which excluded shipped (IN_REVIEW) tasks and so
        // never auto-approved a CI-fix stage's edits to its own worktree.
        Task active = ctx.taskId() == null
                ? null
                : taskStore.findTaskById(ctx.taskId()).orElse(null);
        if (active == null || !taskStore.isAcceptEdits(active.id())) {
            return ApprovalStepResult.cont();
        }
        String worktree = active.worktreePath();
        if (worktree == null || worktree.isBlank()) {
            return ApprovalStepResult.cont();
        }
        String target = filePath(ctx.toolInput());
        if (target == null || !isInside(worktree, target)) {
            // Couldn't pin the target, or it's outside the sandbox — fall
            // through so the user still sees an approval prompt for it.
            return ApprovalStepResult.cont();
        }
        return ApprovalStepResult.resolve(
                responses.toolResponse(ctx.id(), responses.allow(ctx.toolInput())));
    }

    private static String filePath(JsonNode input)
    {
        if (input == null || !input.isObject()) {
            return null;
        }
        for (String key : PATH_KEYS) {
            JsonNode node = input.get(key);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    /** True when {@code target} resolves to a path inside {@code worktree}.
     *  Normalises both so a {@code ..} segment can't escape the sandbox.
     *  A relative target resolves against the JVM cwd — almost never the
     *  worktree — so it safely fails the check and prompts. */
    private static boolean isInside(String worktree, String target)
    {
        try {
            Path root = Path.of(worktree).toAbsolutePath().normalize();
            Path path = Path.of(target).toAbsolutePath().normalize();
            return path.startsWith(root);
        }
        catch (RuntimeException e) {
            return false;
        }
    }
}
