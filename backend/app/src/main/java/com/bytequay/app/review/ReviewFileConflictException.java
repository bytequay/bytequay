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
package com.bytequay.app.review;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** A save refused because the file on disk is no longer the version the editor loaded. */
public class ReviewFileConflictException
        extends RuntimeException
{
    private final String currentHash;
    private final Optional<String> currentContent;

    /**
     * Creates a conflict.
     *
     * @param currentHash the SHA-256 of the file's bytes on disk
     * @param currentContent the file's text, empty when it is binary or larger than the read cap
     */
    public ReviewFileConflictException(String currentHash, Optional<String> currentContent)
    {
        super("The file changed on disk");
        this.currentHash = requireNonNull(currentHash, "currentHash is null");
        this.currentContent = requireNonNull(currentContent, "currentContent is null");
    }

    public String currentHash()
    {
        return currentHash;
    }

    public Optional<String> currentContent()
    {
        return currentContent;
    }
}
