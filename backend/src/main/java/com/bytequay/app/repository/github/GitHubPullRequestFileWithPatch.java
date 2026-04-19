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
package com.bytequay.app.repository.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Same endpoint as {@link GitHubPullRequestFile} but retains the {@code patch}
 * field — the unified-diff hunks for this file. Kept separate so the
 * preview/cache path (which doesn't need patches) doesn't accidentally drag
 * several hundred kilobytes of diff text through SQLite.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestFileWithPatch(
        String filename,
        int additions,
        int deletions,
        String status,
        String patch) {}
