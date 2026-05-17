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
 * One row in the tag-driven archive log: a snapshot of the inbox
 * card we removed from Gmail's INBOX in response to a matching
 * {@link EmailTag.Action#ARCHIVE} rule. The Archived view in the
 * email left nav reads from this log so the row can render without
 * a fresh IMAP round-trip (the messages still exist on Gmail; we
 * fetch them live only when the user opens the thread).
 */
public record EmailTagArchiveEntry(
        String accountEmail,
        String gmailThreadId,
        String tagId,
        String subject,
        String fromAddr,
        String snippet,
        Instant receivedAt,
        Instant archivedAt)
{
}
