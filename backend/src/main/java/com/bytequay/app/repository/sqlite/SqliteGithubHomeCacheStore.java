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

import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserStats;
import com.bytequay.app.repository.GithubHomeCacheStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteGithubHomeCacheStore
        implements GithubHomeCacheStore
{
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<RecentEvent>> EVENT_LIST = new TypeReference<>() {};
    private static final TypeReference<List<UserOrg>> ORG_LIST = new TypeReference<>() {};

    private final GithubUserProfileCacheJpaRepository profileRepo;
    private final GithubUserEventCacheJpaRepository eventRepo;
    private final GithubUserStatsCacheJpaRepository statsRepo;
    private final GithubUserOrgsCacheJpaRepository orgsRepo;

    public SqliteGithubHomeCacheStore(
            GithubUserProfileCacheJpaRepository profileRepo,
            GithubUserEventCacheJpaRepository eventRepo,
            GithubUserStatsCacheJpaRepository statsRepo,
            GithubUserOrgsCacheJpaRepository orgsRepo)
    {
        this.profileRepo = requireNonNull(profileRepo, "profileRepo is null");
        this.eventRepo = requireNonNull(eventRepo, "eventRepo is null");
        this.statsRepo = requireNonNull(statsRepo, "statsRepo is null");
        this.orgsRepo = requireNonNull(orgsRepo, "orgsRepo is null");
    }

    @Override
    public Optional<TimedValue<UserProfile>> findProfile(String login)
    {
        return profileRepo.findById(login)
                .map(e -> new TimedValue<>(read(e.getPayload(), UserProfile.class), e.getFetchedAt()));
    }

    @Override
    @Transactional
    public void putProfile(String login, UserProfile profile, Instant fetchedAt)
    {
        GithubUserProfileCacheEntity entity = profileRepo.findById(login)
                .orElseGet(GithubUserProfileCacheEntity::new);
        entity.setLogin(login);
        entity.setPayload(write(profile));
        entity.setFetchedAt(fetchedAt);
        profileRepo.save(entity);
    }

    @Override
    public Optional<TimedValue<List<RecentEvent>>> findEvents(String login, EventFeed feed)
    {
        var key = new GithubUserEventCacheEntity.GithubUserEventCacheKey(login, feed.name());
        return eventRepo.findById(key)
                .map(e -> new TimedValue<>(
                        ImmutableList.copyOf(readList(e.getPayload(), EVENT_LIST)),
                        e.getFetchedAt()));
    }

    @Override
    @Transactional
    public void putEvents(String login, EventFeed feed, List<RecentEvent> events, Instant fetchedAt)
    {
        var key = new GithubUserEventCacheEntity.GithubUserEventCacheKey(login, feed.name());
        GithubUserEventCacheEntity entity = eventRepo.findById(key)
                .orElseGet(GithubUserEventCacheEntity::new);
        entity.setId(key);
        entity.setPayload(write(ImmutableList.copyOf(events)));
        entity.setFetchedAt(fetchedAt);
        eventRepo.save(entity);
    }

    @Override
    public Optional<TimedValue<UserStats>> findStats(String login)
    {
        return statsRepo.findById(login)
                .map(e -> new TimedValue<>(read(e.getPayload(), UserStats.class), e.getFetchedAt()));
    }

    @Override
    @Transactional
    public void putStats(String login, UserStats stats, Instant fetchedAt)
    {
        GithubUserStatsCacheEntity entity = statsRepo.findById(login)
                .orElseGet(GithubUserStatsCacheEntity::new);
        entity.setLogin(login);
        entity.setPayload(write(stats));
        entity.setFetchedAt(fetchedAt);
        statsRepo.save(entity);
    }

    @Override
    public Optional<TimedValue<List<UserOrg>>> findOrgs(String login)
    {
        return orgsRepo.findById(login)
                .map(e -> new TimedValue<>(
                        ImmutableList.copyOf(readList(e.getPayload(), ORG_LIST)),
                        e.getFetchedAt()));
    }

    @Override
    @Transactional
    public void putOrgs(String login, List<UserOrg> orgs, Instant fetchedAt)
    {
        GithubUserOrgsCacheEntity entity = orgsRepo.findById(login)
                .orElseGet(GithubUserOrgsCacheEntity::new);
        entity.setLogin(login);
        entity.setPayload(write(ImmutableList.copyOf(orgs)));
        entity.setFetchedAt(fetchedAt);
        orgsRepo.save(entity);
    }

    private static String write(Object value)
    {
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialise cache payload", e);
        }
    }

    private static <T> T read(String payload, Class<T> type)
    {
        try {
            return MAPPER.readValue(payload, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialise cache payload as " + type.getSimpleName(), e);
        }
    }

    private static <T> List<T> readList(String payload, TypeReference<List<T>> type)
    {
        try {
            return MAPPER.readValue(payload, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialise cache payload list", e);
        }
    }
}
