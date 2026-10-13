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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The app-wide preferences the settings window reads and writes as one value:
 * how long finished task runtime and the local Recent record are kept, how
 * many recent PR details are warmed, and which appearance the window follows.
 *
 * <p>The bounds are what the pickers offer. They exist so a hand-edited
 * settings row cannot stop the retention jobs or hammer GitHub, not to express
 * a product rule.
 */
public record AppPreferences(int taskRetentionDays, int recentRetentionDays, AppTheme theme, int prWarmCount)
{
    public static final AppPreferences DEFAULT = new AppPreferences(7, 14, AppTheme.SYSTEM, 30);

    public AppPreferences
    {
        checkArgument(taskRetentionDays >= 1 && taskRetentionDays <= 90, "taskRetentionDays is out of range");
        checkArgument(recentRetentionDays >= 1 && recentRetentionDays <= 90, "recentRetentionDays is out of range");
        requireNonNull(theme, "theme is null");
        checkArgument(prWarmCount == 10 || prWarmCount == 30, "prWarmCount must be 10 or 30");
    }
}
