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
package com.bytequay.core.store;

import com.bytequay.core.domain.RemoteSource;

import java.util.List;

/** Durable extra Git remotes per workspace; the checkout is reconciled from these rows. */
public interface RemoteSourceStore
{
    List<RemoteSource> findByWorkspace(String workspaceId);

    /** Inserts or updates the URL of one named remote source. */
    void save(RemoteSource remoteSource);

    void delete(String workspaceId, String name);

    void deleteByWorkspace(String workspaceId);
}
