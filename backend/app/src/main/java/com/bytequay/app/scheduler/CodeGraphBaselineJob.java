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
package com.bytequay.app.scheduler;

import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Fetches every workspace's default branch on a slow cadence so the CodeGraph baseline follows
 * merged commits while the application runs. The fetch is the existing workspace code
 * synchronization; the baseline refresh hangs off it. Nothing here runs on a turn path.
 */
@Component
public class CodeGraphBaselineJob
{
    private static final Logger log = LoggerFactory.getLogger(CodeGraphBaselineJob.class);

    private final WorkspaceStore workspaces;
    private final WorkspaceProvisioningService provisioning;
    private final CredentialService credentials;
    private final AppSettingsService appSettings;
    private final CodeGraphService codeGraph;

    public CodeGraphBaselineJob(
            WorkspaceStore workspaces,
            WorkspaceProvisioningService provisioning,
            CredentialService credentials,
            AppSettingsService appSettings,
            CodeGraphService codeGraph)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
    }

    @Scheduled(
            initialDelayString = "${bytequay.codegraph.baseline-initial-delay:PT1M}",
            fixedDelayString = "${bytequay.codegraph.baseline-interval:PT1H}")
    public void tick()
    {
        if (!codeGraph.available() || !credentials.isGitHubAccountConfigured() || appSettings.inQuietHours()) {
            return;
        }
        for (Workspace workspace : workspaces.findAll()) {
            try {
                provisioning.synchronizeCode(workspace.id());
            }
            catch (RuntimeException failure) {
                log.warn("CodeGraph baseline fetch failed for workspace {}: {}", workspace.id(), failure.getMessage());
            }
        }
    }
}
