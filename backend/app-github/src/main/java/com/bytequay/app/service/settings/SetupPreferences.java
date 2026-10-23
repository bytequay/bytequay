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

import static java.util.Objects.requireNonNull;

/** The next setup page to show and the user's consent to use Assistant conversation. */
public record SetupPreferences(Step step, boolean assistantEnabled)
{
    public SetupPreferences
    {
        requireNonNull(step, "step is null");
    }

    public enum Step
    {
        APPEARANCE,
        GITHUB,
        AGENT,
        DEFAULTS,
        AUTOMATION,
        FEATURES,
        COMPLETE,
    }
}
