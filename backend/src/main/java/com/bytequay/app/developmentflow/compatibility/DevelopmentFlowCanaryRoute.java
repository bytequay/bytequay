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
package com.bytequay.app.developmentflow.compatibility;

import org.springframework.stereotype.Component;

/** Permanent creation cutover. Legacy settings remain diagnostics only. */
@Component
public final class DevelopmentFlowCanaryRoute
{
    public boolean routesNewTaskToV2(String workspaceId)
    {
        return workspaceId != null && !workspaceId.isBlank();
    }

    public Snapshot snapshot()
    {
        return new Snapshot(true);
    }

    public record Snapshot(boolean v2Only) {}
}
