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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.service.threads.CheckpointSummariser;
import com.bytequay.app.service.threads.CheckpointSummaryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Closes the top of the three-level memory hierarchy: per-segment ↑
 * Thread Overall ↑ Workspace memory. Reads recent active Thread
 * Overalls, asks the Haiku summariser to fold them into the
 * workspace's persistent {@code memory_md}, and writes the result
 * back through {@link WorkspaceService#setMemory}.
 *
 * <p>Runs on a long cadence (every 30 minutes by default) so it's
 * cheap; the {@link #distill(String)} method is also exposed for
 * manual triggers — see {@code WorkspaceController}'s distill
 * endpoint. Skips quietly when the Anthropic API key isn't
 * configured: the summariser throws, we log the cause once, and the
 * next sweep retries.
 *
 * <p>v1 ships single-workspace; the corpus query is global because
 * the only workspace is {@link WorkspaceService#DEFAULT_WORKSPACE_ID}.
 * When multi-workspace switches on, scope the {@code ThreadCheckpoint}
 * lookup by workspace (Thread → workspace_id is already on the
 * entity).
 */
@Component
public class WorkspaceMemoryDistiller
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemoryDistiller.class);

    /** How many Thread Overalls feed one distillation pass. Plenty
     *  for a project's active surface; not so many that a single
     *  Haiku call becomes expensive. */
    static final int OVERALL_CORPUS_LIMIT = 25;

    private final WorkspaceService workspaces;
    private final ThreadCheckpointStore checkpoints;
    private final CheckpointSummariser summariser;

    public WorkspaceMemoryDistiller(
            WorkspaceService workspaces,
            ThreadCheckpointStore checkpoints,
            CheckpointSummariser summariser)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.checkpoints = requireNonNull(checkpoints, "checkpoints is null");
        this.summariser = requireNonNull(summariser, "summariser is null");
    }

    /**
     * Periodic distillation across every workspace. Runs every 30
     * minutes — cheap relative to per-segment summarisation since the
     * corpus is bounded by {@link #OVERALL_CORPUS_LIMIT}.
     */
    @Scheduled(fixedDelay = 30L * 60 * 1000, initialDelay = 5L * 60 * 1000)
    public void distillAll()
    {
        for (Workspace w : workspaces.list()) {
            try {
                distill(w.id());
            }
            catch (RuntimeException e) {
                // Same defensive pattern as the rest of the periodic
                // jobs: one workspace's failure (typically a missing
                // Anthropic key) shouldn't stop the others.
                log.warn("Workspace memory distillation failed for {}: {}",
                        w.id(), e.getMessage());
            }
        }
    }

    /**
     * Drive one distillation pass for a single workspace. Returns the
     * updated workspace on success or empty when nothing was written
     * (no Overalls to fold in, or no work since the last pass). The
     * caller can render the result without a follow-up GET.
     */
    public Optional<Workspace> distill(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        Workspace current = workspaces.require(workspaceId);
        if (current.isScratch()) {
            // Scratch workspaces don't accrue durable memory — the
            // domain comment is explicit on this. Skip silently so the
            // operator doesn't have to wonder why nothing changed.
            return Optional.empty();
        }
        List<ThreadCheckpoint> corpus = checkpoints.listAllActiveOveralls(OVERALL_CORPUS_LIMIT);
        if (corpus.isEmpty()) {
            log.debug("No Thread Overalls to distil for workspace {}; leaving memory untouched",
                    workspaceId);
            return Optional.empty();
        }
        CheckpointSummaryResult result = summariser.distilWorkspaceMemory(
                current.memoryMd(), corpus);
        Workspace next = workspaces.setMemory(workspaceId, result.summaryMd());
        log.info("Distilled workspace memory for {} from {} thread Overall(s) "
                        + "({} prompt + {} completion tokens, {} milli-USD)",
                workspaceId, corpus.size(),
                result.promptTokens(), result.completionTokens(), result.costUsdMilli());
        return Optional.of(next);
    }
}
