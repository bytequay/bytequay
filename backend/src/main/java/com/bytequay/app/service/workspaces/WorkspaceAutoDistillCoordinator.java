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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.domain.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Creates reviewable distillation previews at each workspace's configured
 * cadence. It never applies them and never scans a quiet or inactive
 * workspace.
 */
@Component
public class WorkspaceAutoDistillCoordinator
{
    private static final Logger log =
            LoggerFactory.getLogger(WorkspaceAutoDistillCoordinator.class);

    private final WorkspaceService workspaces;
    private final WorkspaceConfigurationService configuration;
    private final WorkspaceKnowledgeService knowledge;

    public WorkspaceAutoDistillCoordinator(
            WorkspaceService workspaces,
            WorkspaceConfigurationService configuration,
            WorkspaceKnowledgeService knowledge)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.configuration = requireNonNull(configuration, "configuration is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5 * 60_000)
    public void scan()
    {
        long now = Instant.now().toEpochMilli();
        for (Workspace workspace : workspaces.list()) {
            if (workspace.isScratch()) {
                continue;
            }
            try {
                WorkspaceSettingsDto settings =
                        configuration.settings(workspace.id());
                long cadence = Duration.ofMinutes(
                        settings.distillMinutes()).toMillis();
                if (now - knowledge.latestRunAt(workspace.id()) < cadence
                        || knowledge.hasPendingPreview(workspace.id())
                        || !knowledge.hasActiveTrunksWithNewSource(workspace.id())) {
                    continue;
                }
                knowledge.createThreadPreview(workspace.id());
            }
            catch (RuntimeException e) {
                log.warn("Auto-distill failed for workspace {}: {}",
                        workspace.id(), e.getMessage());
            }
        }
    }
}
