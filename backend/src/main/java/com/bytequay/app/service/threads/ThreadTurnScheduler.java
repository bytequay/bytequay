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

/**
 * Boundary ThreadService uses to queue agent work.
 */
public interface ThreadTurnScheduler
{
    /** Queue a user turn and return its durable turn id. */
    String enqueueTurn(Thread thread, String input);

    /** Cancel queued turns for one thread and return the number cancelled. */
    int cancelQueuedTurns(String threadId);
}
