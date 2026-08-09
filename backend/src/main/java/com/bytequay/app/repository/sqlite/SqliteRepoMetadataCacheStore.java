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

import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.IssueDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
public class SqliteRepoMetadataCacheStore
{
    public record Snapshot(
            List<GitHubUserMatch> users,
            List<IssueDetail.Label> labels,
            Instant fetchedAt) {}

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<GitHubUserMatch>> USERS = new TypeReference<>() {};
    private static final TypeReference<List<IssueDetail.Label>> LABELS = new TypeReference<>() {};

    private final RepoMetadataCacheJpaRepository repo;

    SqliteRepoMetadataCacheStore(RepoMetadataCacheJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> find(String repoFullName)
    {
        return repo.findById(repoFullName)
                .map(entity -> new Snapshot(
                        read(entity.getUsersJson(), USERS),
                        read(entity.getLabelsJson(), LABELS),
                        entity.getFetchedAt()));
    }

    @Transactional
    public void save(String repoFullName, List<GitHubUserMatch> users, List<IssueDetail.Label> labels, Instant fetchedAt)
    {
        RepoMetadataCacheEntity entity = repo.findById(repoFullName).orElseGet(RepoMetadataCacheEntity::new);
        entity.setRepoFullName(repoFullName);
        entity.setUsersJson(write(ImmutableList.copyOf(users)));
        entity.setLabelsJson(write(ImmutableList.copyOf(labels)));
        entity.setFetchedAt(fetchedAt);
        repo.save(entity);
    }

    private static String write(Object value)
    {
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialise repository metadata cache", e);
        }
    }

    private static <T> List<T> read(String value, TypeReference<List<T>> type)
    {
        try {
            return ImmutableList.copyOf(MAPPER.readValue(value, type));
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialise repository metadata cache", e);
        }
    }
}
