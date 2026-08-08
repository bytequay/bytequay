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
package com.bytequay.app.service.distillation;

import com.bytequay.app.domain.DistillationSignal;
import com.bytequay.app.repository.sqlite.DistillationSignalStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
public class DistillationSignalServiceImpl
{
    private static final Logger log = LoggerFactory.getLogger(DistillationSignalServiceImpl.class);

    private final DistillationSignalStore store;
    private final ObjectMapper mapper;

    public DistillationSignalServiceImpl(DistillationSignalStore store, ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }
    public void record(
            String eventType,
            String sourceId,
            String userDecision,
            String reason,
            Map<String, ?> contextSnapshot,
            String threadId,
            String workspaceId)
    {
        try {
            String json = contextSnapshot == null ? "{}" : mapper.writeValueAsString(contextSnapshot);
            store.save(new DistillationSignal(
                    UUID.randomUUID().toString(),
                    eventType,
                    sourceId,
                    userDecision,
                    reason,
                    json,
                    threadId,
                    workspaceId,
                    Instant.now()));
        }
        catch (JsonProcessingException | RuntimeException e) {
            // Best-effort audit: a distillation write must never break the
            // user action it's recording.
            log.warn("failed to record distillation signal {} for {}: {}",
                    eventType, sourceId, e.getMessage());
        }
    }
}
