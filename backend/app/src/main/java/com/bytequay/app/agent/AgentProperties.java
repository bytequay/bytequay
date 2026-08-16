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
package com.bytequay.app.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

@ConfigurationProperties("bytequay.agent")
public record AgentProperties(
        Path runtimeRoot,
        Duration maximumTurnRuntime,
        String desktopSessionToken,
        String codexExecutable,
        boolean codexEnabled,
        String claudeExecutable,
        boolean claudeEnabled,
        boolean deterministicEnabled)
{
    public AgentProperties
    {
        requireNonNull(runtimeRoot, "runtimeRoot is null");
        requireNonNull(maximumTurnRuntime, "maximumTurnRuntime is null");
        requireNonNull(desktopSessionToken, "desktopSessionToken is null");
        requireNonNull(codexExecutable, "codexExecutable is null");
        requireNonNull(claudeExecutable, "claudeExecutable is null");
        if (codexExecutable.isBlank()) {
            throw new IllegalArgumentException("codexExecutable is blank");
        }
        if (claudeExecutable.isBlank()) {
            throw new IllegalArgumentException("claudeExecutable is blank");
        }
        if (maximumTurnRuntime.isZero() || maximumTurnRuntime.isNegative()) {
            throw new IllegalArgumentException("maximumTurnRuntime must be positive");
        }
    }
}
