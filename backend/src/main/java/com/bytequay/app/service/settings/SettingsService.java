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

import com.bytequay.app.domain.SyncSettings;
import com.bytequay.app.service.WorkspaceBehaviorService.Settings;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * User-visible app settings — workspace-behavior preferences and the
 * PR sync cadence. The endpoints are thin wrappers over the underlying
 * stores; the REST contract lives on this interface so the controller
 * stays free of business logic.
 */
@RequestMapping("/api/settings")
public interface SettingsService
{
    /** GET /api/settings/workspace-behavior */
    @GetMapping("/workspace-behavior")
    Settings getWorkspaceBehavior();

    /** PUT /api/settings/workspace-behavior */
    @PutMapping("/workspace-behavior")
    Settings updateWorkspaceBehavior(@RequestBody Settings body);

    /** GET /api/settings/sync — current sync configuration. */
    @GetMapping("/sync")
    SyncSettings getSyncSettings();

    /** PUT /api/settings/sync — updates the sync interval. Change takes
     *  effect within the next scheduler tick (≤ 10 s). */
    @PutMapping("/sync")
    SyncSettings updateSyncSettings(@RequestBody SyncSettings settings);

    /** POST /api/settings/sync/trigger — schedules an immediate sync
     *  on the next scheduler tick (within 10 s). */
    @PostMapping("/sync/trigger")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void triggerSync();
}
