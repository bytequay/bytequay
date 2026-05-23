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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * Durable scheduler observability for one thread turn.
 *
 * <p>{@code taskId} mirrors the turn's task: an event inherits the
 * Task its parent turn was bound to, or {@code null} for a trunk
 * planning turn.
 */
public record ThreadTurnEvent(
        String id,
        String turnId,
        String threadId,
        String taskId,
        ThreadTurnEventType event,
        Instant createdAt,
        String message)
{
}
