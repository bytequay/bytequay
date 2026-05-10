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
import java.util.List;

/**
 * Full message projection — meta plus body, rendered when the user
 * opens a message in the preview pane. Both {@code bodyText} and
 * {@code bodyHtml} may be present (multipart/alternative emails) or
 * just one. The renderer prefers HTML when available and falls back
 * to text when not.
 *
 * <p>Recipient lists ({@code to}, {@code cc}) are joined into a single
 * comma-separated string per header — the renderer is free to split
 * them. {@code labels} carries Gmail's label IDs (INBOX, UNREAD,
 * STARRED, IMPORTANT, plus user-defined).
 */
public record EmailMessageDetail(
        String id,
        String threadId,
        String from,
        String to,
        String cc,
        String subject,
        Instant receivedAt,
        boolean unread,
        List<String> labels,
        String bodyText,
        String bodyHtml)
{
}
