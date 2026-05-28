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

/**
 * Boundary ThreadService uses to queue agent work.
 */
public interface ThreadTurnScheduler
{
    /** Queue a user (attended) turn and return its durable turn id. */
    String enqueueTurn(Thread thread, String input);

    /** Queue a turn stamped with an explicit initiator — automated
     *  triggers use this to mark the turn unattended so the approval
     *  gate escalates rather than waits for a human. */
    String enqueueTurn(Thread thread, String input, TurnInitiator initiator);

    /** Queue a trunk-scope turn — forces {@code task_id = null} on the
     *  persisted row so the trunk planning agent picks it up regardless
     *  of any foreground Task on the thread. */
    String enqueueTrunkTurn(Thread thread, String input);

    /** Cancel queued turns for one thread and return the number cancelled. */
    int cancelQueuedTurns(String threadId);
}
