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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.workspaces.SyncRetrospectiveWriter;
import com.bytequay.app.service.workspaces.WorkspaceKnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Runs the merged run's last turn and stores what it wrote. The agent authors the
 * memory; this persists it — the same split as every other side effect in the loop.
 */
@Component
public class HarnessRetrospectiveWriter
        implements SyncRetrospectiveWriter
{
    private static final Logger log = LoggerFactory.getLogger(HarnessRetrospectiveWriter.class);

    private final HarnessRepairAgent agent;
    private final WorkspaceKnowledgeService knowledge;

    public HarnessRetrospectiveWriter(
            HarnessRepairAgent agent, WorkspaceKnowledgeService knowledge)
    {
        this.agent = requireNonNull(agent, "agent is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
    }

    @Override
    public void write(
            Path worktree,
            String workspaceId,
            Integer prNumber,
            long budgetMilliUsd,
            String resumeSessionId)
    {
        if (budgetMilliUsd < 100) {
            // Out of budget at the end of a run is not worth a park — the work is
            // merged and the only thing lost is the memory.
            log.info("skipping the retrospective for {}: no budget left", workspaceId);
            return;
        }
        HarnessRepairAgent.Outcome outcome = agent.retrospective(
                worktree, workspaceId, prNumber, budgetMilliUsd, resumeSessionId, null);
        for (HarnessRepairAgent.Learned entry : outcome.learned()) {
            try {
                knowledge.saveKnowledge(
                        workspaceId, null, entry.title(), entry.body(), List.of("ci-fix"),
                        Map.of("syncRunPr", prNumber == null ? "" : String.valueOf(prNumber)));
            }
            catch (RuntimeException notLearned) {
                log.warn("could not store a retrospective entry for {}: {}",
                        workspaceId, notLearned.getMessage());
            }
        }
    }
}
