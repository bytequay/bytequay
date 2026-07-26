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
import com.bytequay.app.domain.StageType;
import org.springframework.stereotype.Service;

import static java.util.Objects.requireNonNull;

/** Ensures the single long-lived Remote Development stage exists. */
@Service
public class RemoteDevelopmentStageService
{
    private final StageStateMachine stageMachine;

    public RemoteDevelopmentStageService(StageStateMachine stageMachine)
    {
        this.stageMachine = requireNonNull(stageMachine, "stageMachine is null");
    }

    public StageInstance ensureOpen(String taskId)
    {
        return stageMachine.ensurePhaseOpen(
                taskId, StageType.REMOTE_DEVELOPMENT_STAGE, null);
    }

    /** Same-transaction form for an enclosing task command. */
    public StageInstance ensureOpenInCommand(String taskId)
    {
        return stageMachine.ensurePhaseOpenInCommand(
                taskId, StageType.REMOTE_DEVELOPMENT_STAGE, null);
    }
}
