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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.service.threads.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** Projects exact-head Remote readiness without owning any lifecycle state. */
@Component
public final class V2ReadinessNotificationProjector
        implements ExecutionPorts.MaintenanceWork
{
    private final Store store;
    private final NotificationService notifications;
    private final ObjectMapper json;

    public V2ReadinessNotificationProjector(
            Store store,
            NotificationService notifications,
            ObjectMapper json)
    {
        this.store = requireNonNull(store, "store is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public void maintain(Instant now)
    {
        requireNonNull(now, "now is null");
        store.project(now, this::notifyReady);
    }

    private void notifyReady(ReadyNotification ready)
    {
        String dedupKey = "development-flow:ready-to-merge:"
                + ready.stageId() + ":" + ready.stageVersion();
        notifications.createCanonical(
                NotificationKind.READY_TO_MERGE,
                ready.workspaceId(),
                ready.trunkId(),
                ready.taskId(),
                "review-request",
                "Pull request ready to merge",
                "PR #" + ready.prNumber() + " is ready on " + shortSha(ready.headSha()),
                "#/workspace/" + ready.workspaceId() + "/trunks/" + ready.trunkId(),
                dedupKey,
                payload(ready));
    }

    private String payload(ReadyNotification ready)
    {
        try {
            return json.writeValueAsString(Map.of(
                    "taskId", ready.taskId(),
                    "stageId", ready.stageId(),
                    "readinessEvidenceId", ready.readinessEvidenceId(),
                    "headSha", ready.headSha(),
                    "prNumber", ready.prNumber()));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("unable to encode readiness notification", e);
        }
    }

    private static String shortSha(String sha)
    {
        return sha.substring(0, Math.min(12, sha.length()));
    }

    @FunctionalInterface
    public interface Store
    {
        /** Claims each new edge and delivers it in the same transaction. */
        void project(Instant now, Consumer<ReadyNotification> delivery);
    }

    public record ReadyNotification(
            String workspaceId,
            String trunkId,
            String taskId,
            String stageId,
            long stageVersion,
            String readinessEvidenceId,
            String headSha,
            int prNumber)
    {
        public ReadyNotification
        {
            requireNonNull(workspaceId, "workspaceId is null");
            requireNonNull(trunkId, "trunkId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(stageId, "stageId is null");
            requireNonNull(readinessEvidenceId, "readinessEvidenceId is null");
            requireNonNull(headSha, "headSha is null");
        }
    }
}
