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
 * Per-commit drill-in detail — subject line plus the rest of the
 * commit message body. Lazy-fetched when a commit is selected so
 * the listCommits payload stays small even on branches with long
 * release-note style commits. The body is whatever git's
 * {@code %B} format emits after the first line (no trailing
 * newline trimming).
 */
public record LocalCommitDetail(
        String sha,
        String subject,
        String body) {}
