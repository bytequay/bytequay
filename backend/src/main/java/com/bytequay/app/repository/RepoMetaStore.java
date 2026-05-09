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
package com.bytequay.app.repository;

import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.StoredRepoMeta;

import java.time.Instant;
import java.util.Optional;

/**
 * Local store for repo-level metadata (description, license, topics,
 * languages, counts). Backs the Repository overview / About panel and
 * lets the frontend paint instantly from the local row instead of
 * waiting on GitHub on every page mount.
 *
 * <p>Reads return the stored {@link RepoMeta} together with the
 * {@code synced_at} timestamp so the service layer can decide whether
 * the row is fresh enough or needs a background refresh.
 */
public interface RepoMetaStore
{
    Optional<StoredRepoMeta> find(String owner, String repo);

    void save(String owner, String repo, RepoMeta meta, Instant syncedAt);
}
