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
import com.bytequay.app.repository.DistillationSignalStore;
import com.bytequay.app.repository.MemoryItemStore;
import com.bytequay.app.repository.PermissionGrantStore;
import com.bytequay.app.repository.SurfaceVisitStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestWorkspaceDataPurger
{
    private final MemoryItemStore memoryItems = mock(MemoryItemStore.class);
    private final PermissionGrantStore permissionGrants = mock(PermissionGrantStore.class);
    private final DistillationSignalStore distillationSignals = mock(DistillationSignalStore.class);
    private final SurfaceVisitStore surfaceVisits = mock(SurfaceVisitStore.class);
    private final WorkspaceDataPurger purger = new WorkspaceDataPurger(
            memoryItems, permissionGrants, distillationSignals, surfaceVisits);

    @Test
    void purgeThreadScopedDeletesThreadAndTaskScopedRows()
    {
        purger.purgeThreadScoped("t1", List.of("k1", "k2"));

        verify(memoryItems).deleteByScope(MemoryItemScopeKind.THREAD, "t1");
        verify(distillationSignals).deleteByThread("t1");
        verify(surfaceVisits).deleteByThread("t1");
        verify(permissionGrants).deleteForScope("thread", "t1");
        verify(permissionGrants).deleteForScope("task", "k1");
        verify(permissionGrants).deleteForScope("task", "k2");
    }

    @Test
    void purgeThreadScopedWithNoTasksSkipsTaskScopedDeletes()
    {
        purger.purgeThreadScoped("t1", List.of());

        verify(memoryItems).deleteByScope(MemoryItemScopeKind.THREAD, "t1");
        verify(distillationSignals).deleteByThread("t1");
        verify(surfaceVisits).deleteByThread("t1");
        verify(permissionGrants).deleteForScope("thread", "t1");
        verify(permissionGrants, never()).deleteForScope(eq("task"), any());
    }

    @Test
    void purgeWorkspaceScopedDeletesWorkspaceScopedRows()
    {
        purger.purgeWorkspaceScoped("ws1");

        verify(memoryItems).deleteByScope(MemoryItemScopeKind.WORKSPACE, "ws1");
        verify(permissionGrants).deleteForScope("workspace", "ws1");
        verify(distillationSignals).deleteByWorkspace("ws1");
    }
}
