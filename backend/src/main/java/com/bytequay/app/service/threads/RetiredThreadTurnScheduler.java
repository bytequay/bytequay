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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.TurnInitiator;
import org.springframework.stereotype.Component;

/**
 * Fail-closed adapter retained only while historical mutation APIs are
 * removed from their compatibility services. It owns no queue, worker,
 * callback, state transition, capacity lease, or recovery loop.
 */
@Component
public final class RetiredThreadTurnScheduler
        implements ThreadTurnScheduler
{
    private static UnsupportedOperationException retired()
    {
        return new UnsupportedOperationException(
                "LEGACY turn execution is retired; use a typed V2 control");
    }

    @Override
    public String enqueueTrunkTurn(Thread thread, String input)
    {
        throw retired();
    }

    @Override
    public String enqueueTaskTurn(Thread thread, String input, String taskId)
    {
        throw retired();
    }

    @Override
    public String enqueueStageTurn(
            Thread thread,
            String input,
            String taskId,
            String stageId,
            TurnInitiator initiator)
    {
        throw retired();
    }

    @Override
    public int cancelQueuedTurns(String threadId)
    {
        return 0;
    }
}
