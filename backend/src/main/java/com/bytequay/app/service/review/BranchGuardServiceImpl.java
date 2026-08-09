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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.repository.BranchGuardStore;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static java.util.Objects.requireNonNull;

@Service
public class BranchGuardServiceImpl
{
    private final BranchGuardStore store;

    BranchGuardServiceImpl(BranchGuardStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }
    @Transactional
    public BranchGuard get(String taskId)
    {
        // Read-only: must NOT persist a row. The stage/brain views call this
        // on every load, long before a task ever pushes — if that lazily
        // saved a disabled row, enableOnFirstPush's "only act if absent"
        // check below would find it already present and skip arming the
        // guard, permanently stuck disabled despite having pushed.
        return store.findByTask(taskId).orElseGet(() -> BranchGuard.disabled(taskId));
    }
    @Transactional
    public BranchGuard update(String taskId, Boolean enabled, String schedule)
    {
        return TaskPhaseMachine.withTaskLock(taskId, () -> {
            BranchGuard guard = get(taskId);
            if (enabled != null) {
                guard = guard.withEnabled(enabled);
            }
            if (schedule != null && !schedule.isBlank()) {
                guard = guard.withSchedule(schedule);
            }
            return store.save(guard);
        });
    }
}
