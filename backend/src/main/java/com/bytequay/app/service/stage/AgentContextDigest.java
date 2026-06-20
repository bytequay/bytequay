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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.StageStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Builds the cross-agent context digest an agent's session is preloaded
 * with — a newest-first bullet list of a task's recent iteration summaries,
 * capped to a token budget. The brain agent prepends this to its system
 * prompt at session creation; review-panel agents reuse it later.
 *
 * <p>The token budget is enforced by a rough {@code length / 4} estimate
 * and trimming from the oldest end, so the newest summaries always survive.
 * Good enough for v1's cost band; a real tokenizer can replace
 * {@link #estimateTokens} when per-task token accounting lands.
 */
@Component
public class AgentContextDigest
{
    /** Default system-prompt digest budget (Decision 1's 16k cap). */
    public static final int DEFAULT_CAP_TOKENS = 16_000;

    private static final int MAX_SUMMARIES = 50;

    private final IterationStore iterationStore;
    private final StageStore stageStore;

    public AgentContextDigest(IterationStore iterationStore, StageStore stageStore)
    {
        this.iterationStore = requireNonNull(iterationStore, "iterationStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
    }

    /**
     * Build the digest for a task, capped at {@code capTokens}. Returns a
     * placeholder block when the task has no summaries yet. Never null.
     */
    public String build(String taskId, int capTokens)
    {
        if (taskId == null) {
            return placeholder();
        }
        // Newest-first; trimming the tail drops the oldest first.
        List<TaskStageIteration> summaries =
                new ArrayList<>(iterationStore.findRecentSummaries(taskId, MAX_SUMMARIES));
        if (summaries.isEmpty()) {
            return placeholder();
        }
        String digest = render(summaries);
        while (estimateTokens(digest) > capTokens && summaries.size() > 1) {
            summaries.remove(summaries.size() - 1);
            digest = render(summaries);
        }
        return digest;
    }

    private String render(List<TaskStageIteration> summaries)
    {
        StringBuilder body = new StringBuilder("## Recent task activity\n");
        for (TaskStageIteration it : summaries) {
            body.append("- ")
                    .append(stageDisplay(it.stageId()))
                    .append(" #").append(it.iterationNumber())
                    .append(" [").append(it.trigger()).append("]: ")
                    .append(it.summaryText() == null ? "" : it.summaryText())
                    .append('\n');
        }
        body.append("\n## How to answer\n")
                .append("- Use your tools for precise data; don't speculate.\n")
                .append("- When referencing a stage, mention it by full name.\n")
                .append("- Keep responses to 6 sentences or fewer.");
        return body.toString();
    }

    private String stageDisplay(UUID stageId)
    {
        return Optional.ofNullable(stageId)
                .flatMap(stageStore::findStageById)
                .map(s -> displayName(s.type()))
                .orElse("Stage");
    }

    private static String displayName(StageType type)
    {
        return switch (type) {
            case DEVELOPMENT_STAGE -> "DevelopmentStage";
            case CI_FIXING_STAGE -> "CiFixingStage";
            case REVIEW_MONITOR_STAGE -> "ReviewMonitorStage";
            case CLEANUP_STAGE -> "CleanupStage";
            case REVIEW_STAGE -> "ReviewStage";
        };
    }

    private static String placeholder()
    {
        return "## Recent task activity\n_(no iteration summaries yet)_";
    }

    int estimateTokens(String text)
    {
        return text == null ? 0 : text.length() / 4;
    }
}
