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
package com.bytequay.app.web;

import com.bytequay.app.beans.workspace.WorkspaceAutomationStatusDto;
import com.bytequay.app.beans.workspace.WorkspaceCreationDto;
import com.bytequay.app.beans.workspace.WorkspaceOnboardingDto;
import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.service.workspaces.WorkspaceConfigurationService;
import com.bytequay.app.service.workspaces.WorkspaceCreationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;
import static org.springframework.http.HttpStatus.GONE;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class WorkspaceConfigurationController
{
    private final WorkspaceConfigurationService configuration;
    private final WorkspaceCreationService creations;

    public WorkspaceConfigurationController(
            WorkspaceConfigurationService configuration,
            WorkspaceCreationService creations)
    {
        this.configuration = requireNonNull(
                configuration, "configuration is null");
        this.creations = requireNonNull(creations, "creations is null");
    }

    @GetMapping("/settings")
    public WorkspaceSettingsDto settings(@PathVariable String workspaceId)
    {
        return configuration.settings(workspaceId);
    }

    @PutMapping("/settings")
    public WorkspaceSettingsDto saveSettings(
            @PathVariable String workspaceId,
            @RequestBody WorkspaceSettingsDto settings)
    {
        if (settings.qualityScanEnabled()
                || settings.remoteIssueIntakeEnabled()) {
            throw new ResponseStatusException(
                    GONE, "Automatic legacy Task creation is retired");
        }
        return configuration.saveSettings(workspaceId, settings);
    }

    @GetMapping("/automation")
    public WorkspaceAutomationStatusDto automation(@PathVariable String workspaceId)
    {
        return new WorkspaceAutomationStatusDto(
                new WorkspaceAutomationStatusDto.QualityScan(
                        false, false, "Automatic Task creation is retired",
                        false, null, null, "RETIRED", 0, null),
                new WorkspaceAutomationStatusDto.RemoteIssueIntake(
                        false, false, "Automatic Task creation is retired",
                        false, null, null, "RETIRED", 0, 0, 0, null));
    }

    @GetMapping("/onboarding")
    public WorkspaceOnboardingDto onboarding(@PathVariable String workspaceId)
    {
        return configuration.onboarding(workspaceId);
    }

    @PostMapping("/onboarding/dismiss")
    public WorkspaceOnboardingDto dismissOnboarding(
            @PathVariable String workspaceId)
    {
        return configuration.dismissOnboarding(workspaceId);
    }

    @PostMapping("/detach")
    public void detach(@PathVariable String workspaceId)
    {
        configuration.detach(workspaceId);
    }

    @PostMapping("/reconnect")
    public void reconnect(@PathVariable String workspaceId)
    {
        configuration.reconnect(workspaceId);
    }

    @PostMapping("/reclone")
    public WorkspaceCreationDto reclone(@PathVariable String workspaceId)
    {
        return creations.reclone(workspaceId);
    }
}
