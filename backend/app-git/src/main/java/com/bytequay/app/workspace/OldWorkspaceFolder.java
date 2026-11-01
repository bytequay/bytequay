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
package com.bytequay.app.workspace;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * A ByteQuay workspace folder that no workspace in the current database owns.
 *
 * @param id the folder's name, which is the workspace ID it was created for
 * @param path absolute folder path
 * @param repository the clone's origin as {@code owner/name}, when it can be read
 * @param sizeBytes total size of the files in the folder
 * @param modifiedAt newest change time of the folder, its clone, or one of its checkouts
 * @param checkouts number of task checkouts in the folder
 */
public record OldWorkspaceFolder(String id, String path, Optional<String> repository, long sizeBytes, Instant modifiedAt, int checkouts)
{
    public OldWorkspaceFolder
    {
        requireNonNull(id, "id is null");
        requireNonNull(path, "path is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(modifiedAt, "modifiedAt is null");
    }
}
