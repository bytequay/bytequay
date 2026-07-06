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
 * One commit on a {@link PR}. Local commits are unpushed
 * ({@code pushedAt == null}) and may be amended/reset during the local
 * phase; {@code pushedAt} is stamped on the Remote Push transition.
 */
public record PRCommit(
        String id,
        String prId,
        String sha,
        String message,
        int additions,
        int deletions,
        Instant authoredAt,
        Instant pushedAt) {}
