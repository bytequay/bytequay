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

import com.bytequay.app.service.settings.AppSettingsService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/settings")
public class SettingsController
{
    private final ModelSettingsService models;
    private final AppSettingsService settings;

    public SettingsController(ModelSettingsService models, AppSettingsService settings)
    {
        this.models = requireNonNull(models, "models is null");
        this.settings = requireNonNull(settings, "settings is null");
    }

    @GetMapping("/models")
    public ModelSettings models()
    {
        return models.get();
    }

    @PutMapping("/models/{configId}/selection")
    public ModelSettings select(@PathVariable String configId, @RequestBody SelectionRequest request)
    {
        models.select(configId, request.model(), Optional.ofNullable(request.effort()));
        return models.get();
    }

    @PutMapping("/models/main")
    public ModelSettings setMain(@RequestBody MainRequest request)
    {
        models.setMain(request.configId());
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

    public record SelectionRequest(String model, String effort) {}

    public record MainRequest(String configId) {}

    public record ApiKeyRequest(String provider, String alias, String key) {}
}
