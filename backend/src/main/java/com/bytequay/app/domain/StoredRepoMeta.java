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
 * Persisted snapshot of a repository's metadata, paired with the time
 * it was last refreshed from GitHub. Drives the stale-while-revalidate
 * read path in {@code RepoService.getRepoMeta}: callers compare
 * {@link #syncedAt} against a freshness window and decide whether to
 * kick off a background refresh.
 */
public record StoredRepoMeta(RepoMeta meta, Instant syncedAt) {}
