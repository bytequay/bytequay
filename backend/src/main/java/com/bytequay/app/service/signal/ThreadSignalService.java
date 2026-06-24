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
package com.bytequay.app.service.signal;

import com.bytequay.app.domain.ThreadSignal;

import java.util.List;

/**
 * The per-thread passive signal feed behind the trunk's Notifications
 * tab. Distinct from the actionable {@code NotificationService}: signals
 * are inert browse-only events. {@code record} is called from event
 * listeners at M8 trigger points; the tab reads {@code list} and flips
 * {@code markRead} when the user opens a row.
 */
public interface ThreadSignalService
{
    /** Append a signal to a thread's feed; returns the persisted row.
     *  {@code taskId}, {@code body}, and {@code sourceUrl} are nullable. */
    ThreadSignal record(
            String threadId,
            String taskId,
            String sourceKind,
            String iconKind,
            String title,
            String body,
            String sourceUrl);

    /** Signals on a thread, newest-first. */
    List<ThreadSignal> list(String threadId);

    /** Mark a signal read. No-op when the id is unknown. */
    void markRead(String id);
}
