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

import com.bytequay.app.domain.DevReport;
import com.bytequay.app.domain.DevReport.Decision;
import com.bytequay.app.domain.DevReport.TestMapEntry;
import com.bytequay.app.domain.DevReport.TrickySpot;
import com.bytequay.app.repository.DevReportStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Component
class SqliteDevReportStore
        implements DevReportStore
{
    private static final Logger log = LoggerFactory.getLogger(SqliteDevReportStore.class);

    private final DevReportJpaRepository reports;
    private final ObjectMapper mapper;

    SqliteDevReportStore(DevReportJpaRepository reports, ObjectMapper mapper)
    {
        this.reports = requireNonNull(reports, "reports is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    @Transactional
    public DevReport save(DevReport report)
    {
        DevReportEntity e = reports.findByTaskId(report.taskId()).orElseGet(DevReportEntity::new);
        e.setId(e.getId() == null ? UUID.randomUUID().toString() : e.getId());
        e.setTaskId(report.taskId());
        e.setSummary(report.summary());
        e.setDecisionsJson(toJson(report.decisions()));
        e.setInvariantsJson(toJson(report.invariants()));
        e.setTrickySpotsJson(toJson(report.trickySpots()));
        e.setTestMapJson(toJson(report.testMap()));
        e.setFollowupsJson(toJson(report.followups()));
        e.setCreatedAtMs(report.createdAt().toEpochMilli());
        return toDomain(reports.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DevReport> findByTask(String taskId)
    {
        return reports.findByTaskId(taskId).map(this::toDomain);
    }

    private DevReport toDomain(DevReportEntity e)
    {
        return new DevReport(
                e.getId(),
                e.getTaskId(),
                e.getSummary(),
                fromJson(e.getDecisionsJson(), new TypeReference<List<Decision>>() {}),
                fromJson(e.getInvariantsJson(), new TypeReference<List<String>>() {}),
                fromJson(e.getTrickySpotsJson(), new TypeReference<List<TrickySpot>>() {}),
                fromJson(e.getTestMapJson(), new TypeReference<List<TestMapEntry>>() {}),
                fromJson(e.getFollowupsJson(), new TypeReference<List<String>>() {}),
                Instant.ofEpochMilli(e.getCreatedAtMs()));
    }

    private <T> List<T> fromJson(String json, TypeReference<List<T>> type)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            log.warn("unparseable dev_report json: {}", e.getMessage());
            return List.of();
        }
    }

    private String toJson(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("dev report JSON serialise failed", e);
        }
    }
}
