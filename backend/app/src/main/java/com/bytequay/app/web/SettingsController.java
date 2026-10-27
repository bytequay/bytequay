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

import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppPreferences;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ClaudeAccount;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService.ModelSettings;
import com.bytequay.app.service.settings.QuietHours;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/settings")
public class SettingsController
{
    private final ModelSettingsService models;
    private final AppSettingsService settings;
    private final WorkspaceStore workspaces;

    public SettingsController(ModelSettingsService models, AppSettingsService settings, WorkspaceStore workspaces)
    {
        this.models = requireNonNull(models, "models is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    /**
     * @param workspaceId resolves each agent type through that workspace's
     * overrides, so a composer opened inside a workspace is seeded with the
     * model its next task will actually launch with.
     */
    @GetMapping("/models")
    public ModelSettings models(@RequestParam(required = false) String workspaceId)
    {
        return models.get(Optional.ofNullable(workspaceId));
    }

    @PostMapping("/models/refresh")
    public ModelSettings refreshModels(@RequestParam(defaultValue = "false") boolean force, @RequestParam(required = false) String workspaceId)
    {
        models.refreshCatalogs(force);
        return models.get(Optional.ofNullable(workspaceId));
    }

    @PutMapping("/models/agents/{type}")
    public ModelSettings setAgentModel(@PathVariable AgentModelType type, @RequestBody SelectionRequest request)
    {
        models.setAgentModel(type, request.configId(), request.model(), Optional.ofNullable(request.effort()));
        return models.get();
    }

    /** Clears the type's stored value so it inherits again: Review from Development, Development from the catalog. */
    @DeleteMapping("/models/agents/{type}")
    public ModelSettings clearAgentModel(@PathVariable AgentModelType type)
    {
        models.clearAgentModel(type);
        return models.get();
    }

    @PostMapping("/api-keys")
    public ModelSettings addApiKey(@RequestBody ApiKeyRequest request)
    {
        checkText(request.key(), "key");
        return models.addApiKey(request.provider(), request.alias(), request.key());
    }

    @PostMapping("/claude-accounts")
    public ModelSettings addClaudeAccount(@RequestBody ClaudeAccountRequest request)
    {
        checkText(request.label(), "account name");
        checkText(request.email(), "account email");
        Optional<String> workspaceId = Optional.ofNullable(request.workspaceId());
        workspaceId.ifPresent(this::requireWorkspace);
        ClaudeAccount account = models.addClaudeAccount(request.label(), request.email(), workspaceId);
        // After the account's transaction commits, so the check never holds the database connection.
        models.checkNewLane(account.laneId());
        return models.get(workspaceId);
    }

    @PostMapping("/claude-accounts/gateway")
    public ModelSettings addClaudeGateway(@RequestBody ClaudeGatewayRequest request)
    {
        checkText(request.label(), "account name");
        checkText(request.gatewayUrl(), "gateway URL");
        checkText(request.token(), "gateway token");
        Optional<String> workspaceId = Optional.ofNullable(request.workspaceId());
        workspaceId.ifPresent(this::requireWorkspace);
        ClaudeAccount account = models.addClaudeGateway(request.label(), request.gatewayUrl(), request.token(), workspaceId);
        models.checkNewLane(account.laneId());
        return models.get(workspaceId);
    }

    @PutMapping("/claude-accounts/{id}/workspace")
    public ModelSettings restrictClaudeAccount(@PathVariable String id, @RequestBody AccountWorkspaceRequest request)
    {
        requireWorkspace(request.workspaceId());
        models.restrictClaudeAccount("claude-account-" + id, request.workspaceId());
        return models.get(Optional.of(request.workspaceId()));
    }

    private void requireWorkspace(String workspaceId)
    {
        checkText(workspaceId, "workspaceId");
        checkArgument(workspaces.findById(workspaceId).isPresent(), "Unknown workspace");
    }

    @DeleteMapping("/api-keys/{alias}")
    public ModelSettings removeApiKey(@PathVariable String alias)
    {
        return models.removeApiKey(alias);
    }

    @DeleteMapping("/claude-accounts/{id}")
    public ModelSettings removeClaudeAccount(@PathVariable String id)
    {
        // Settings already hide a removed account's lane, so nothing needs checking again.
        models.removeClaudeAccount(id);
        return models.get();
    }

    @GetMapping("/app")
    public AppPreferences preferences()
    {
        return settings.preferences();
    }

    @PutMapping("/app")
    public AppPreferences savePreferences(@RequestBody AppPreferences request)
    {
        return settings.savePreferences(request);
    }

    @GetMapping("/development-automation")
    public DevelopmentAutomationDto developmentAutomation()
    {
        return DevelopmentAutomationDto.from(settings.developmentAutomation());
    }

    @PutMapping("/development-automation")
    public DevelopmentAutomationDto saveDevelopmentAutomation(@RequestBody DevelopmentAutomationDto request)
    {
        return DevelopmentAutomationDto.from(settings.saveDevelopmentAutomation(request.toDomain()));
    }

    @GetMapping("/quiet-hours")
    public QuietHours quietHours()
    {
        return settings.quietHours();
    }

    @PutMapping("/quiet-hours")
    public QuietHours saveQuietHours(@RequestBody QuietHours request)
    {
        return settings.saveQuietHours(request);
    }

    public record SelectionRequest(String configId, String model, String effort) {}

    public record ApiKeyRequest(String provider, String alias, String key) {}

    public record ClaudeAccountRequest(String label, String email, String workspaceId) {}

    public record ClaudeGatewayRequest(String label, String gatewayUrl, String token, String workspaceId) {}

    public record AccountWorkspaceRequest(String workspaceId) {}
}
