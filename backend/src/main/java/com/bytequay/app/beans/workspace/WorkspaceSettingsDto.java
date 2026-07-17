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
package com.bytequay.app.beans.workspace;

import java.util.List;
import java.util.Map;

/** Typed payload persisted as one JSON row per workspace. */
public record WorkspaceSettingsDto(
        double sessionCapUsd,
        double dailyCapUsd,
        boolean pauseAtCap,
        int syncSeconds,
        int brainBudgetChars,
        int distillMinutes,
        List<String> kbAudiences,
        Map<String, String> providers,
        boolean notifyCi,
        boolean notifyCompletions)
{
    public static WorkspaceSettingsDto defaults()
    {
        return new WorkspaceSettingsDto(
                1.0,
                10.0,
                true,
                60,
                8_000,
                30,
                List.of("plan", "dev", "review", "ci-fix"),
                Map.of(),
                true,
                false);
    }
}
