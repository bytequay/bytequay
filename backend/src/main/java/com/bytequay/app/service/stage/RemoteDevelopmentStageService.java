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

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.StageStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static java.util.Objects.requireNonNull;

/** Ensures the single long-lived Remote Development stage exists. */
@Service
public class RemoteDevelopmentStageService
{
    private final StageStore stageStore;
    private final StageBudgetService budgetService;

    public RemoteDevelopmentStageService(StageStore stageStore, StageBudgetService budgetService)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.budgetService = requireNonNull(budgetService, "budgetService is null");
    }

    @Transactional
    public StageInstance ensureOpen(String taskId)
    {
        StageInstance stage = stageStore.findStageByType(taskId, StageType.REMOTE_DEVELOPMENT_STAGE)
                .map(found -> found.state() == StageState.CLOSED
                        ? stageStore.reopenStage(found.id())
                        : found)
                .orElseGet(() -> {
                    StageInstance opened = stageStore.openStage(taskId, StageType.REMOTE_DEVELOPMENT_STAGE, null);
                    budgetService.onStageOpened(opened);
                    return opened;
                });
        if (stage.state() == StageState.CLOSED) {
            return stageStore.reopenStage(stage.id());
        }
        return stage;
    }
}
