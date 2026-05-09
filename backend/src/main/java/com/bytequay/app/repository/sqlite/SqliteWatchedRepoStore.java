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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteWatchedRepoStore
        implements WatchedRepoStore
{
    private final WatchedRepoJpaRepository jpaRepository;

    public SqliteWatchedRepoStore(WatchedRepoJpaRepository jpaRepository)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
    }

    @Override
    public List<WatchedRepo> findAll()
    {
        return jpaRepository.findAllByOrderByDisplayOrderAsc().stream()
                .map(SqliteWatchedRepoStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public Optional<WatchedRepo> find(String owner, String repo)
    {
        return jpaRepository.findByOwnerAndRepo(owner, repo).map(SqliteWatchedRepoStore::toDomain);
    }

    @Override
    public WatchedRepo add(String owner, String repo)
    {
        if (jpaRepository.findByOwnerAndRepo(owner, repo).isPresent()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409), owner + "/" + repo + " is already watched");
        }
        int nextOrder = jpaRepository.findAllByOrderByDisplayOrderAsc().stream()
                .mapToInt(WatchedRepoEntity::getDisplayOrder)
                .max()
                .orElse(-1) + 1;
        WatchedRepoEntity saved = jpaRepository.save(new WatchedRepoEntity(owner, repo, nextOrder));
        return toDomain(saved);
    }

    @Override
    public void remove(String owner, String repo)
    {
        jpaRepository.findByOwnerAndRepo(owner, repo)
                .ifPresent(e -> jpaRepository.deleteById(e.getId()));
    }

    @Override
    public void setLocalClonePath(String owner, String repo, String localClonePath)
    {
        WatchedRepoEntity entity = jpaRepository.findByOwnerAndRepo(owner, repo)
                .orElseThrow(() -> new IllegalArgumentException(owner + "/" + repo + " is not watched"));
        entity.setLocalClonePath(localClonePath);
        jpaRepository.save(entity);
    }

    @Override
    public void setUpstreamRemoteName(String owner, String repo, String upstreamRemoteName)
    {
        WatchedRepoEntity entity = jpaRepository.findByOwnerAndRepo(owner, repo)
                .orElseThrow(() -> new IllegalArgumentException(owner + "/" + repo + " is not watched"));
        entity.setUpstreamRemoteName(upstreamRemoteName);
        jpaRepository.save(entity);
    }

    @Override
    public void setViewFocus(String owner, String repo, String viewFocus)
    {
        WatchedRepoEntity entity = jpaRepository.findByOwnerAndRepo(owner, repo)
                .orElseThrow(() -> new IllegalArgumentException(owner + "/" + repo + " is not watched"));
        entity.setViewFocus(viewFocus);
        jpaRepository.save(entity);
    }

    private static WatchedRepo toDomain(WatchedRepoEntity e)
    {
        return new WatchedRepo(e.getId(), e.getOwner(), e.getRepo(),
                e.getDisplayOrder(), e.getLocalClonePath(), e.getUpstreamRemoteName(),
                e.getViewFocus());
    }
}
