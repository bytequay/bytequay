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

import com.bytequay.app.agent.codegraph.CodeGraphBaseline;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppPreferences;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.CodeGraphSetting;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService.ModelSettings;
import com.bytequay.app.service.settings.QuietHours;
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
import java.util.function.Supplier;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/settings")
public class SettingsController
{
    private final ModelSettingsService models;
    private final AppSettingsService settings;
    private final Supplier<CodeGraphBaseline> baseline;

    public SettingsController(
            ModelSettingsService models,
            AppSettingsService settings,
            Supplier<CodeGraphBaseline> baseline)
    {
        this.models = requireNonNull(models, "models is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.baseline = requireNonNull(baseline, "baseline is null");
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
        requireNotBlank(request.key(), "key must not be blank");
        return models.addApiKey(request.provider(), request.alias(), request.key());
    }

    @DeleteMapping("/api-keys/{alias}")
    public ModelSettings removeApiKey(@PathVariable String alias)
    {
        return models.removeApiKey(alias);
    }

    @GetMapping("/codegraph")
    public CodeGraphSetting codeGraph()
    {
        return new CodeGraphSetting(settings.codeGraphMode());
    }

    @GetMapping("/codegraph/baseline")
    public CodeGraphBaseline codeGraphBaseline()
    {
        return baseline.get();
    }

    @PutMapping("/codegraph")
    public CodeGraphSetting saveCodeGraph(@RequestBody CodeGraphSetting request)
    {
        return settings.saveCodeGraph(request);
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
}
