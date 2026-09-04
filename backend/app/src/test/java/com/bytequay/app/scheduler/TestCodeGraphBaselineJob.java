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

import com.bytequay.app.agent.codegraph.FakeCodeGraphService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCodeGraphBaselineJob
{
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final FakeCodeGraphService codeGraph = new FakeCodeGraphService();
    private final CodeGraphBaselineJob job = new CodeGraphBaselineJob(workspaces, provisioning, credentials, appSettings, codeGraph);

    @BeforeEach
    void setUp()
    {
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace("first"), workspace("second")));
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        when(appSettings.inQuietHours()).thenReturn(false);
    }

    private static Workspace workspace(String id)
    {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        RepoRef repository = RepoRef.of("owner", id);
        return new Workspace(
                id,
                id,
                100,
                repository,
                Optional.empty(),
                Optional.empty(),
                100,
                repository,
                Topology.DIRECT,
                "main",
                "https://github.com/owner/" + id + ".git",
                Optional.empty(),
                Optional.empty(),
                "/tmp/bytequay-job-test/" + id + "/repository",
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
    }

    @Test
    void fetchesEveryWorkspaceAndContinuesPastAFailure()
    {
        when(provisioning.synchronizeCode("first")).thenThrow(new IllegalStateException("offline"));

        job.tick();

        verify(provisioning).synchronizeCode("first");
        verify(provisioning).synchronizeCode("second");
    }

    @Test
    void staysQuietWithoutTheHelper()
    {
        codeGraph.available = false;

        job.tick();

        verify(provisioning, never()).synchronizeCode("first");
    }

    @Test
    void staysQuietDuringQuietHoursOrWithoutAnAccount()
    {
        when(appSettings.inQuietHours()).thenReturn(true);
        job.tick();
        when(appSettings.inQuietHours()).thenReturn(false);
        when(credentials.isGitHubAccountConfigured()).thenReturn(false);
        job.tick();

        verify(provisioning, never()).synchronizeCode("first");
    }
}
