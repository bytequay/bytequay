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

/**
 * What a {@link Notification} is about. Values mirror the parked
 * states on {@link TaskStatus} plus a couple of informational ones.
 * Stored as TEXT in {@code notifications.kind} so adding a new kind
 * doesn't need a migration; the renderer dispatches on this to pick
 * the icon, copy, and click target.
 */
public enum NotificationKind
{
    /** A headless run finished with a proposed diff + reply and is
     *  holding at the publish gate. Click → diff viewer + Approve. */
    AWAITING_REVIEW,

    /** A headless run is stuck and needs the human to weigh in.
     *  Click → thread detail; the user's reply takes the lease back. */
    NEEDS_ATTENTION,

    /** Ship-and-continue or an auto-fix completed without further
     *  attention required (PR merged, CI green, etc.). Informational. */
    AUTO_FIX_DONE,
}
