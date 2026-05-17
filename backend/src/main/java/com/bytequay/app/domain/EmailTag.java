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
 * A user-defined classification rule for the email surface. Matches a
 * case-insensitive substring against an inbox thread's display
 * subject and assigns the configured {@link Action} when it hits.
 *
 * <p>Precedence between multiple matching tags is fixed (see
 * {@link EmailThreadMeta.View}); ordering is not stored per rule.
 */
public record EmailTag(
        String id,
        String accountEmail,
        String name,
        String subjectContains,
        Action action,
        Instant createdAt,
        Instant updatedAt)
{
    /** What happens to a thread that matches the tag's pattern. */
    public enum Action
    {
        /** Pass the thread through to Inbox and surface it under the tag in the left nav. */
        FOCUS,
        /** Remove INBOX on Gmail; the thread shows in the app's Archived view via the local log. */
        ARCHIVE,
        /** Drop the thread from the app entirely; no Gmail-side change. */
        IGNORE
    }
}
