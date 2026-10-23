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
package com.bytequay.app.repository.github;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Result of a conditional GitHub list request. Empty {@code values} means the
 * endpoint answered 304 and the caller's stored copy is still current; the
 * ETag is always the freshest one seen, so it can be stored either way.
 */
public record ConditionalList<T>(Optional<List<T>> values, Optional<String> etag)
{
    public ConditionalList
    {
        requireNonNull(values, "values is null");
        requireNonNull(etag, "etag is null");
        values = values.map(ImmutableList::copyOf);
    }

    public boolean unchanged()
    {
        return values.isEmpty();
    }

    public List<T> orElse(List<T> stored)
    {
        return values.orElse(stored);
    }

    public <R> ConditionalList<R> map(java.util.function.Function<List<T>, List<R>> mapper)
    {
        return new ConditionalList<>(values.map(mapper), etag);
    }
}
