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
package com.bytequay.app.agent.codegraph;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * What the settings page says about the shared baselines: how many
 * repositories are indexed and when the most recent one was last brought
 * current. Derived from the baseline directory on request; nothing is stored.
 *
 * @param updatedAt absent when no repository is indexed yet.
 */
public record CodeGraphBaseline(int repositories, Optional<Instant> updatedAt)
{
    public static final CodeGraphBaseline NONE = new CodeGraphBaseline(0, Optional.empty());

    public CodeGraphBaseline
    {
        requireNonNull(updatedAt, "updatedAt is null");
    }
}
