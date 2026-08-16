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
package com.bytequay.agent.core;

import java.nio.file.Path;

import static com.bytequay.agent.spi.AgentChecks.checkPresent;

public record TaskResources(
        Path runtimeDirectory,
        Path eventsFile,
        Path blobsDirectory,
        Path providerDirectory)
{
    public TaskResources
    {
        checkPresent(runtimeDirectory, "runtimeDirectory");
        checkPresent(eventsFile, "eventsFile");
        checkPresent(blobsDirectory, "blobsDirectory");
        checkPresent(providerDirectory, "providerDirectory");
        Path root = runtimeDirectory.toAbsolutePath().normalize();
        if (!runtimeDirectory.isAbsolute() || !eventsFile.isAbsolute() || !blobsDirectory.isAbsolute() || !providerDirectory.isAbsolute()) {
            throw new IllegalArgumentException("task resource path is not absolute");
        }
        if (!eventsFile.toAbsolutePath().normalize().startsWith(root)
                || !blobsDirectory.toAbsolutePath().normalize().startsWith(root)
                || !providerDirectory.toAbsolutePath().normalize().startsWith(root)) {
            throw new IllegalArgumentException("task resource escapes runtimeDirectory");
        }
    }
}
