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

/**
 * A ref the assistant's read tool handed out on one day, which a later message that day may act on.
 *
 * @param kind the kind of page that named the ref first
 * @param target where the ref opens; empty for a repository, which opens no page of its own
 */
public record ServedRef(String kind, Optional<AgendaTarget> target)
{
    public ServedRef
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(target, "target is null");
    }
}
