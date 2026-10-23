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
package com.bytequay.app.artifact;

import com.bytequay.core.domain.Artifact;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A conversation as the list shows it: the conversation and how many pages it made, each counted once whatever its versions. */
public record ArtifactRow(Artifact artifact, int pages)
{
    public ArtifactRow
    {
        requireNonNull(artifact, "artifact is null");
        checkArgument(pages >= 0, "pages is negative");
    }
}
