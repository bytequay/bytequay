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

import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.repository.sqlite.DistillationSignalStore;
import com.bytequay.app.repository.sqlite.PermissionGrantStore;
import com.bytequay.app.repository.sqlite.SqliteMemoryItemStore;
import com.bytequay.app.repository.sqlite.SurfaceVisitStore;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Centralised cleanup for the FK-less "side tables" nothing cascades to.
 * Deleting a workspace (or purging a thread) drops the core rows via the
 * schema's FK cascades, but a handful of tables key off ids by value with
 * no foreign key — they'd leak rows forever without an explicit sweep. This
 * component is the one place that sweep lives, shared by
 * {@link WorkspaceService} and the thread teardown.
 */
@Component
public class WorkspaceDataPurger
{
    private final SqliteMemoryItemStore memoryItems;
    private final PermissionGrantStore permissionGrants;
    private final DistillationSignalStore distillationSignals;
    private final SurfaceVisitStore surfaceVisits;

    public WorkspaceDataPurger(
            SqliteMemoryItemStore memoryItems,
            PermissionGrantStore permissionGrants,
            DistillationSignalStore distillationSignals,
            SurfaceVisitStore surfaceVisits)
    {
        this.memoryItems = requireNonNull(memoryItems, "memoryItems is null");
        this.permissionGrants = requireNonNull(permissionGrants, "permissionGrants is null");
        this.distillationSignals = requireNonNull(distillationSignals, "distillationSignals is null");
        this.surfaceVisits = requireNonNull(surfaceVisits, "surfaceVisits is null");
    }

    /** Remove thread- and task-scoped rows in FK-less side tables — nothing
     *  cascades to them, so a thread teardown must delete them explicitly. */
    public void purgeThreadScoped(String threadId, List<String> taskIds)
    {
        requireNonNull(threadId, "threadId is null");
        memoryItems.deleteByScope(MemoryItemScopeKind.THREAD, threadId);
        distillationSignals.deleteByThread(threadId);
        surfaceVisits.deleteByThread(threadId);
        permissionGrants.deleteForScope("thread", threadId);
        if (taskIds != null) {
            for (String taskId : taskIds) {
                permissionGrants.deleteForScope("task", taskId);
            }
        }
    }

    /** Remove workspace-scoped rows in FK-less side tables. */
    public void purgeWorkspaceScoped(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        memoryItems.deleteByScope(MemoryItemScopeKind.WORKSPACE, workspaceId);
        permissionGrants.deleteForScope("workspace", workspaceId);
        distillationSignals.deleteByWorkspace(workspaceId);
    }
}
