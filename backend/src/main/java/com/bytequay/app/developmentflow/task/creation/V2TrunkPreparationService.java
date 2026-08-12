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
package com.bytequay.app.developmentflow.task.creation;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.bytequay.app.service.workmodel.WorkModelService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Maintains immutable engine snapshots for read-only V2 Trunks. */
@Component
public final class V2TrunkPreparationService
{
    private final TaskCommandExecutor commands;
    private final JdbcTemplate jdbc;
    private final ThreadEngineOverrides engines;
    private final WorkModelResolver resolver;
    private final WorkModelService workModels;

    public V2TrunkPreparationService(
            TaskCommandExecutor commands,
            JdbcTemplate jdbc,
            ThreadEngineOverrides engines,
            WorkModelResolver resolver,
            WorkModelService workModels)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.engines = requireNonNull(engines, "engines is null");
        this.resolver = requireNonNull(resolver, "resolver is null");
        this.workModels = requireNonNull(workModels, "workModels is null");
    }

    public boolean routes(String workspaceId)
    {
        return workspaceId != null && !workspaceId.isBlank();
    }

    public int repairExistingTrunkEngineSnapshots()
    {
        List<TrunkRef> incomplete = jdbc.query("""
                SELECT trunk.id, trunk.workspace_id
                FROM threads trunk
                WHERE trunk.turn_version = 'V2'
                  AND (
                      SELECT COUNT(DISTINCT engine.audience)
                      FROM thread_engines engine
                      WHERE engine.thread_id = trunk.id
                        AND engine.audience IN ('plan', 'dev', 'review', 'ci-fix')
                        AND CASE
                            WHEN json_valid(engine.work_model_json) = 1
                            THEN NULLIF(TRIM(json_extract(
                                    engine.work_model_json, '$.model')), '') IS NOT NULL
                            ELSE 0
                        END
                  ) < 4
                ORDER BY trunk.id
                """, (rs, rowNum) -> new TrunkRef(
                rs.getString("id"), rs.getString("workspace_id")));
        incomplete.forEach(trunk -> prepareTrunk(trunk.id(), trunk.workspaceId()));
        return incomplete.size();
    }

    public void prepareTrunk(String trunkId, String workspaceId)
    {
        requireText(trunkId, "trunkId");
        requireText(workspaceId, "workspaceId");
        commands.executeVoid("v2-trunk/" + trunkId,
                () -> prepareTrunkInCommand(trunkId, workspaceId, false));
    }

    public void prepareNewTrunk(String trunkId, String workspaceId)
    {
        requireText(trunkId, "trunkId");
        requireText(workspaceId, "workspaceId");
        if (!routes(workspaceId)) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "New Trunk preparation requires its creation transaction");
        }
        prepareTrunkInCommand(trunkId, workspaceId, true);
    }

    private void prepareTrunkInCommand(
            String trunkId, String workspaceId, boolean newlyCreated)
    {
        Integer owned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM threads
                WHERE id = ? AND workspace_id = ?
                """, Integer.class, trunkId, workspaceId);
        if (owned == null || owned != 1) {
            throw new IllegalArgumentException(
                    "Trunk does not belong to the routed Workspace");
        }
        String currentVersion = jdbc.queryForObject("""
                SELECT turn_version FROM threads
                WHERE id = ? AND workspace_id = ?
                """, String.class, trunkId, workspaceId);
        if ("V2".equals(currentVersion)) {
            EngineSnapshot snapshot = completeEngineSnapshot(trunkId, workspaceId);
            if (snapshot.repairRequired()) {
                engines.replace(trunkId, snapshot.engines());
            }
            return;
        }
        if (!"LEGACY".equals(currentVersion)) {
            throw new IllegalStateException(
                    "Trunk has unknown turn version " + currentVersion);
        }
        if (!newlyCreated) {
            throw new IllegalStateException(
                    "Historical LEGACY Trunk is read-only and cannot be promoted to V2");
        }

        EngineSnapshot snapshot = completeEngineSnapshot(trunkId, workspaceId);
        engines.replace(trunkId, snapshot.engines());
        int changed = jdbc.update("""
                UPDATE threads
                SET lifecycle_state = COALESCE(lifecycle_state, 'ACTIVE'),
                    turn_version = 'V2'
                WHERE id = ? AND workspace_id = ? AND turn_version = 'LEGACY'
                  AND NOT EXISTS (
                      SELECT 1 FROM thread_turns legacy
                      WHERE legacy.thread_id = threads.id
                        AND legacy.task_id IS NULL
                        AND (legacy.scope = 'TRUNK' OR legacy.scope IS NULL)
                        AND legacy.status IN ('QUEUED', 'RUNNING'))
                  AND NOT EXISTS (
                      SELECT 1 FROM thread_turn typed
                      WHERE typed.trunk_id = threads.id
                        AND typed.status IN (
                            'REQUESTED','QUEUED','CLAIMED','RUNNING'))
                """, trunkId, workspaceId);
        if (changed != 1) {
            throw new IllegalStateException(
                    "Trunk must be quiescent before V2 preparation");
        }
    }

    private EngineSnapshot completeEngineSnapshot(
            String trunkId, String workspaceId)
    {
        Map<String, WorkModel> snapshot = new LinkedHashMap<>();
        boolean repairRequired = false;
        for (String audience : SessionAudience.ALL) {
            WorkModel existing = engines.forAudience(trunkId, audience).orElse(null);
            WorkModel choice;
            if (existing == null) {
                choice = workModels.freeze(resolver
                        .resolveForWorkspace(workspaceId, audience).choice());
                repairRequired = true;
            }
            else if (!engines.isFrozen(trunkId, audience)) {
                choice = workModels.freeze(existing);
                repairRequired = true;
            }
            else {
                choice = existing;
            }
            snapshot.put(audience, choice);
        }
        return new EngineSnapshot(Map.copyOf(snapshot), repairRequired);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private record EngineSnapshot(
            Map<String, WorkModel> engines,
            boolean repairRequired) {}

    private record TrunkRef(String id, String workspaceId) {}
}
