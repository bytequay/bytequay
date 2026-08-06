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
 * One commit on the Commits tab. Both timestamps are carried because
 * they answer different questions: {@code authoredAt} is when the patch
 * was written, preserved across rebases and amends, and
 * {@code committedAt} is when it landed on this branch. A history list
 * shows the latter — matching github.com — since a maintainer rebasing
 * a contribution lands it today under an author date from days ago.
 * Body and parent shas are deferred to a Commit details drill-in slice.
 */
public record LocalCommit(
        String sha,
        String shortSha,
        String subject,
        String authorName,
        String authorEmail,
        Instant authoredAt,
        Instant committedAt) {}
