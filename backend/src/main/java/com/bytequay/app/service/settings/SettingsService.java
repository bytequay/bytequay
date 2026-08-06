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

/**
 * User-visible app settings — workspace-behavior preferences and the
 * PR sync cadence. The endpoints are thin wrappers over the underlying
 * stores; the controller binds the HTTP surface, this interface
 * declares only the business contract.
 */
public interface SettingsService
{
    Settings getWorkspaceBehavior();

    Settings updateWorkspaceBehavior(Settings body);

    /** Current sync configuration. */
    SyncSettings getSyncSettings();

    /** Update the sync interval. Change takes effect within the next
     *  scheduler tick (≤ 10 s). */
    SyncSettings updateSyncSettings(SyncSettings settings);

    /** Schedule an immediate sync on the next scheduler tick (within
     *  10 s). */
    void triggerSync();
}
