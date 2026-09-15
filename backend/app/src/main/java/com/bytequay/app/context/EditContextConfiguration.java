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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RegisteredContextSource;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** User-selected preference/catalog changes; absent fields retain their current values. */
public record EditContextConfiguration(
        Optional<Boolean> useInReviews,
        Optional<List<Source>> sources,
        Optional<List<ContextScope>> scopes)
{
    public EditContextConfiguration
    {
        requireNonNull(useInReviews, "useInReviews is null");
        sources = requireNonNull(sources, "sources is null").map(ImmutableList::copyOf);
        scopes = requireNonNull(scopes, "scopes is null").map(ImmutableList::copyOf);
    }

    /** Editable source identity/location; provenance is assigned by the service. */
    public record Source(String id, RegisteredContextSource.Kind kind, String url) {}
}
