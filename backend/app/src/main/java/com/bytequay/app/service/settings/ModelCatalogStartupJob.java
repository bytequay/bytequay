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
package com.bytequay.app.service.settings;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Seeds the probe-free model catalogs at startup, then re-probes them off the startup thread. Installed CLIs are never
 * updated here: a CLI below ByteQuay's minimum reports the command that upgrades it, and the user runs it.
 */
@Component
public class ModelCatalogStartupJob
{
    private final ModelSettingsService models;

    public ModelCatalogStartupJob(ModelSettingsService models)
    {
        this.models = requireNonNull(models, "models is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady()
    {
        models.seedCatalogs();
        Thread.ofVirtual().name("bytequay-model-catalogs").start(models::refreshCatalogs);
    }
}
