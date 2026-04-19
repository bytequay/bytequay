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
 * A single file in a pull-request diff, with the unified-diff patch text
 * included. Separate from {@link PullRequestDetail.ChangedFile} because the
 * preview/cache path deliberately omits patches (they can be large and aren't
 * worth persisting in SQLite).
 *
 * @param patch unified diff hunks for this file; {@code null} for binary or
 *              renamed-without-content-change files
 */
public record DiffFile(
        String filename,
        String status,
        int additions,
        int deletions,
        String patch) {}
