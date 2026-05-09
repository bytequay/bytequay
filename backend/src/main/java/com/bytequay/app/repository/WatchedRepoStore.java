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

import com.bytequay.app.domain.WatchedRepo;

import java.util.List;
import java.util.Optional;

public interface WatchedRepoStore
{
    List<WatchedRepo> findAll();

    Optional<WatchedRepo> find(String owner, String repo);

    WatchedRepo add(String owner, String repo);

    void remove(String owner, String repo);

    /** Updates the local clone path for a watched repo. Pass null to
     *  unmap (e.g. when the user moves / deletes the directory).
     *  Throws IllegalArgumentException if the repo isn't watched. */
    void setLocalClonePath(String owner, String repo, String localClonePath);

    /** Records the name of the git remote that points at the watched
     *  repo (typically "upstream" in fork-based clones). Pass null
     *  when the repo is unmapped or when origin already points at the
     *  watched repo. Throws IllegalArgumentException if the repo
     *  isn't watched. */
    void setUpstreamRemoteName(String owner, String repo, String upstreamRemoteName);

    /** Records the user's choice for the repo detail page's commits-
     *  tab focus: {@code "fork"} or {@code "upstream"}. Pass null to
     *  unset and let the service resolve a default. Throws
     *  IllegalArgumentException if the repo isn't watched. */
    void setViewFocus(String owner, String repo, String viewFocus);
}
