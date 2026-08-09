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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.repository.sqlite.SqliteStageStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Read projection for historical LEGACY stage budgets. */
@Component
public class StageBudgetService
{
    private static final Logger log = LoggerFactory.getLogger(StageBudgetService.class);

    private final SqliteStageStore stages;
    private final ObjectMapper mapper;

    public StageBudgetService(SqliteStageStore stages, ObjectMapper mapper)
    {
        this.stages = requireNonNull(stages, "stages is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public void onStageOpened(StageInstance stage)
    {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "LEGACY stage budgets are read-only; use typed V2 CI-repair recovery");
    }

    StageMetrics readMetrics(UUID stageId)
    {
        String json = stages.findMetricsJson(stageId).orElse(null);
        if (json == null || json.isBlank()) {
            return StageMetrics.empty();
        }
        try {
            return mapper.readValue(json, StageMetrics.class);
        }
        catch (JsonProcessingException e) {
            log.warn("unparseable metrics_json for stage {}: {}", stageId, e.getMessage());
            return StageMetrics.empty();
        }
    }
}
