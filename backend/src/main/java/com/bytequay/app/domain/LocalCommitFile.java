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
 * One file touched by a commit. Powers the middle pane of the Commits
 * tab — the file tree the user picks from to load a per-file diff into
 * the right pane.
 *
 * <p>{@link #status} mirrors git's {@code --name-status} short codes:
 * {@code A} added, {@code M} modified, {@code D} deleted,
 * {@code R} renamed, {@code C} copied, {@code T} type-changed.
 * additions/deletions are -1 for binary files (git emits {@code -}).
 */
public record LocalCommitFile(
        String path,
        String status,
        int additions,
        int deletions) {}
