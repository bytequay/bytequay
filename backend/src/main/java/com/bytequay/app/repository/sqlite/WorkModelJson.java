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

import com.bytequay.app.domain.WorkModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON conversion for the optional {@link WorkModel} override that tasks,
 * threads and workspaces each persist in a single column. Every store held an
 * identical {@code serialiseWorkModel} / {@code deserialiseWorkModel} pair;
 * centralising it keeps the null handling and failure policy consistent.
 *
 * <p>A serialise failure is a programming error and surfaces loudly; a
 * deserialise failure on a stale or bad row is treated as "no override" so a
 * single corrupt column never breaks the whole load.
 */
public final class WorkModelJson
{
    private WorkModelJson() {}

    /** JSON for persistence; a {@code null} model stays null. */
    public static String serialise(ObjectMapper mapper, WorkModel model)
    {
        if (model == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(model);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("WorkModel JSON serialise failed", e);
        }
    }

    /** Model from a persisted column; null/blank/bad JSON resolves to no override. */
    public static WorkModel deserialise(ObjectMapper mapper, String json)
    {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, WorkModel.class);
        }
        catch (JsonProcessingException e) {
            return null;
        }
    }
}
