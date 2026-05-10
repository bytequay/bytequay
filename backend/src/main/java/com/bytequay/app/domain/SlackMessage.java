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
 * One message ingested from Slack into the local cache. Composite key is
 * {@code (workspaceId, channelId, ts)} — Slack guarantees ts uniqueness
 * per channel.
 *
 * <p>{@code rawJson} is the verbatim Slack {@code message} object. We
 * keep it because the polling loop is the only place that ever talks to
 * Slack: persisting the raw payload means a future field need (reactions,
 * attachments, edits) can be served from the cache without a re-fetch.
 *
 * <p>{@code threadTs} is non-null for thread replies; for the parent of a
 * thread Slack sets {@code thread_ts == ts} (we mirror that). {@code text}
 * is nullable because Slack's "message_changed" / file-share variants can
 * land with the text on a sub-object.
 */
public record SlackMessage(
        String workspaceId,
        String channelId,
        String ts,
        String userId,
        String text,
        String threadTs,
        boolean hasAtYou,
        SlackInboxKind inboxKind,
        String rawJson,
        Instant fetchedAt) {}
