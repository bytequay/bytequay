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
package com.bytequay.app.developmentflow.stage.persistence;

import com.bytequay.app.developmentflow.stage.CancellationToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.CleanupStageManager;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

public final class StagePersistenceTestSupport
{
    private StagePersistenceTestSupport() {}

    public static CancellationToCleanupHandoff cancellationToCleanup(
            TaskCommandExecutor commands, JdbcTemplate jdbc, TaskManager tasks)
    {
        V2StageStore store = new V2StageStore(jdbc);
        return new CancellationToCleanupHandoff(
                commands,
                new PlanStageManager(commands, store),
                tasks,
                new CleanupStageManager(commands, store));
    }
}
