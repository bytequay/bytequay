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
package com.bytequay.app.beans.signal;

import com.bytequay.app.domain.ThreadSignal;

/** Wire shape of a {@link ThreadSignal}. {@code createdAt} / {@code readAt}
 *  are epoch-millis ({@code readAt} null until read). */
public record ThreadSignalDto(
        String id,
        String threadId,
        String taskId,
        String sourceKind,
        String iconKind,
        String title,
        String body,
        String sourceUrl,
        long createdAt,
        Long readAt)
{
    public static ThreadSignalDto from(ThreadSignal s)
    {
        return new ThreadSignalDto(
                s.id(),
                s.threadId(),
                s.taskId(),
                s.sourceKind(),
                s.iconKind(),
                s.title(),
                s.body(),
                s.sourceUrl(),
                s.createdAt().toEpochMilli(),
                s.readAt() == null ? null : s.readAt().toEpochMilli());
    }
}
