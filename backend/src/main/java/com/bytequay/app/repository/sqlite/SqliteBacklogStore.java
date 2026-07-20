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

import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.repository.BacklogStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
class SqliteBacklogStore
        implements BacklogStore
{
    private final BacklogItemJpaRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    SqliteBacklogStore(
            BacklogItemJpaRepository repository,
            JdbcTemplate jdbc,
            ObjectMapper mapper)
    {
        this.repository = repository;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public BacklogItem save(BacklogItem item)
    {
        BacklogItemEntity entity = new BacklogItemEntity();
        entity.setId(item.id());
        entity.setThreadId(item.threadId());
        entity.setTitle(item.title());
        entity.setBody(item.body());
        entity.setTags(item.tags());
        entity.setCreatedAtMs(item.createdAt().toEpochMilli());
        entity.setStartedAtMs(epochOrNull(item.startedAt()));
        entity.setLinkedTaskId(item.linkedTaskId());
        entity.setWorkspaceId(item.workspaceId());
        entity.setPriority(item.priority());
        entity.setSource(item.source());
        entity.setStatus(item.status());
        entity.setCreatedBy(item.createdBy());
        entity.setOrigin(item.origin());
        entity.setInProgressAtMs(epochOrNull(item.inProgressAt()));
        entity.setResolvedAtMs(epochOrNull(item.resolvedAt()));
        entity.setRejectedAtMs(epochOrNull(item.rejectedAt()));
        entity.setRejectionReason(item.rejectionReason());
        entity.setRelatedBacklogIds(item.relatedBacklogIds());
        entity.setItemKey(item.itemKey());
        entity.setSummary(item.summary());
        entity.setDetail(item.detail());
        entity.setImpactRisk(item.impactRisk());
        entity.setLinksJson(writeLinks(item.links()));
        return toDomain(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BacklogItem> findByThread(String threadId)
    {
        return repository.findByThreadIdOrderByCreatedAtMsAsc(threadId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BacklogItem> findByWorkspace(String workspaceId)
    {
        return repository.findByWorkspaceIdOrderByCreatedAtMsDesc(workspaceId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BacklogItem> findById(String id)
    {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BacklogItem> findByWorkspaceAndItemKey(String workspaceId, String itemKey)
    {
        return repository.findByWorkspaceIdAndItemKey(workspaceId, itemKey).map(this::toDomain);
    }

    @Override
    @Transactional
    public String nextItemKey(String workspaceId)
    {
        jdbc.update("""
                INSERT INTO workspace_backlog_seq (workspace_id, next_value)
                VALUES (?, 1)
                ON CONFLICT(workspace_id) DO NOTHING
                """, workspaceId);
        Integer value = jdbc.queryForObject("""
                UPDATE workspace_backlog_seq
                SET next_value = next_value + 1
                WHERE workspace_id = ?
                RETURNING next_value - 1
                """, Integer.class, workspaceId);
        if (value == null) {
            throw new IllegalStateException(
                    "no backlog sequence for workspace " + workspaceId);
        }
        return "BQ-" + value;
    }

    @Override
    @Transactional
    public void delete(String id)
    {
        repository.deleteById(id);
    }

    private BacklogItem toDomain(BacklogItemEntity e)
    {
        return new BacklogItem(
                e.getId(),
                e.getThreadId(),
                e.getWorkspaceId(),
                e.getTitle(),
                e.getBody(),
                e.getTags(),
                e.getPriority(),
                e.getSource(),
                e.getStatus(),
                e.getCreatedBy(),
                e.getOrigin(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                instantOrNull(e.getInProgressAtMs()),
                instantOrNull(e.getStartedAtMs()),
                instantOrNull(e.getResolvedAtMs()),
                instantOrNull(e.getRejectedAtMs()),
                e.getRejectionReason(),
                e.getLinkedTaskId(),
                e.getRelatedBacklogIds(),
                e.getItemKey(),
                e.getSummary(),
                e.getDetail(),
                e.getImpactRisk(),
                readLinks(e.getLinksJson()));
    }

    private String writeLinks(List<BacklogItem.Link> links)
    {
        try {
            return mapper.writeValueAsString(links);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode backlog links", e);
        }
    }

    private List<BacklogItem.Link> readLinks(String json)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(
                    json, new TypeReference<List<BacklogItem.Link>>() {});
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid backlog links", e);
        }
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instantOrNull(Long epochMs)
    {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }
}
