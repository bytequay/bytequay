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
 * Lightweight projection of a single email — just the data the inbox
 * list view needs. Body intentionally omitted; lazy-fetched when the
 * user opens the message in the preview pane.
 *
 * <p>{@code from} is the raw {@code From} header value (may be
 * {@code "Display Name <email@host>"} or just {@code "email@host"});
 * the renderer is responsible for extracting whichever piece it
 * wants to show. {@code receivedAt} is parsed from Gmail's
 * {@code internalDate} (milliseconds since epoch).
 */
public record EmailMessageMeta(
        String id,
        String threadId,
        String from,
        String subject,
        String snippet,
        Instant receivedAt,
        boolean unread)
{
}
