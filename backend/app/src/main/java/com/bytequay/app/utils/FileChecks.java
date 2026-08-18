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
package com.bytequay.app.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public final class FileChecks
{
    private FileChecks() {}

    public static Path requireRealDirectory(Path path, String name)
    {
        requireNonNull(path, "path is null");
        requireNonNull(name, "name is null");
        try {
            Path real = path.toRealPath();
            checkArgument(Files.isDirectory(real), name + " is not a directory");
            return real;
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve " + name, e);
        }
    }

    public static Path requireRealFile(Path path, String name)
    {
        requireNonNull(path, "path is null");
        requireNonNull(name, "name is null");
        try {
            Path real = path.toRealPath();
            checkArgument(Files.isRegularFile(real), name + " is not a regular file");
            return real;
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve " + name, e);
        }
    }
}
