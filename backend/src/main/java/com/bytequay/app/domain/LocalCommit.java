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
 * One commit on the Commits tab. Authored timestamp is preferred over
 * committed because rebases and amends update committer date but
 * preserve authorship — author is what the user thinks of as "when I
 * wrote this." Body and parent shas are deferred to a Commit details
 * drill-in slice.
 */
public record LocalCommit(
        String sha,
        String shortSha,
        String subject,
        String authorName,
        String authorEmail,
        Instant authoredAt) {}
