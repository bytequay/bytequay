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

import com.bytequay.agent.core.CodeGraphArm;

import java.util.concurrent.ThreadLocalRandom;

/** How new agent tasks get the CodeGraph tool: always, never, or by coin flip so the two arms can be compared. */
public enum CodeGraphMode
{
    ON,
    OFF,
    COMPARE;

    public CodeGraphArm arm()
    {
        return switch (this) {
            case ON -> CodeGraphArm.ON;
            case OFF -> CodeGraphArm.OFF;
            case COMPARE -> ThreadLocalRandom.current().nextBoolean() ? CodeGraphArm.ON : CodeGraphArm.OFF;
        };
    }
}
