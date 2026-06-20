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
import java.util.UUID;

/**
 * A unified inline review comment on a Task's diff, across all sources.
 * {@code remoteLink} is populated iff {@code source} is
 * {@link ReviewCommentSource#REMOTE_REVIEWER} — a DB check constraint
 * enforces that invariant. Only {@code LOCAL_USER} rows exist today; the
 * agent and remote sources arrive with their write sites later.
 *
 * @param file relative path
 * @param body markdown
 * @param remoteLink github.com discussion link, non-null iff remote-sourced
 */
public record ReviewComment(
        UUID id,
        String taskId,
        String file,
        int line,
        String body,
        Instant createdAt,
        ReviewCommentSource source,
        String remoteLink,
        boolean resolved)
{
}
