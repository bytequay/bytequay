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
package com.bytequay.core.domain;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** The ETags GitHub last returned for a stored default diff's two list endpoints. */
public record PrDiffEtags(Optional<String> files, Optional<String> commits)
{
    private static final PrDiffEtags NONE = new PrDiffEtags(Optional.empty(), Optional.empty());

    public PrDiffEtags
    {
        requireNonNull(files, "files is null");
        requireNonNull(commits, "commits is null");
    }

    public static PrDiffEtags none()
    {
        return NONE;
    }
}
