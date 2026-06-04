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
package com.bytequay.app.service.pr.filters;

import com.bytequay.app.domain.PullRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * Aggregator for every {@link NamedFilter}{@code <PullRequest>}
 * bean. Keeps the rest of the codebase from threading
 * {@code List<NamedFilter<PullRequest>>} through wider service
 * surfaces: one inject of this aggregator gets you name-based
 * lookup and a one-shot apply over a collection.
 *
 * <p>Names are unique — Spring injects every filter bean at
 * construction time and the constructor fails fast if two beans
 * report the same {@link NamedFilter#name()}.
 */
@Component
public class PullRequestFilters
{
    private final Map<String, NamedFilter<PullRequest>> byName;

    public PullRequestFilters(List<NamedFilter<PullRequest>> filters)
    {
        requireNonNull(filters, "filters is null");
        Map<String, NamedFilter<PullRequest>> map = new HashMap<>();
        for (NamedFilter<PullRequest> f : filters) {
            NamedFilter<PullRequest> prior = map.put(f.name(), f);
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate NamedFilter<PullRequest> for name '" + f.name()
                                + "': " + prior + " and " + f);
            }
        }
        this.byName = filters.stream()
                .collect(toUnmodifiableMap(NamedFilter::name, f -> f));
    }

    /** Filter bean for {@code name}, or empty if unknown. */
    public Optional<NamedFilter<PullRequest>> byName(String name)
    {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name));
    }

    /** Every registered filter name. Stable across calls because
     *  the underlying map is immutable. */
    public Set<String> names()
    {
        return byName.keySet();
    }

    /**
     * Apply the filter {@code name} to {@code all} at instant
     * {@code now}, returning only matching PRs in the same relative
     * order they came in. Throws {@link IllegalArgumentException}
     * if {@code name} doesn't resolve — callers surface that as a
     * 400 / tool error.
     */
    public List<PullRequest> apply(String name, List<PullRequest> all, Instant now)
    {
        NamedFilter<PullRequest> filter = byName(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown PR filter: " + name + " (known: " + names() + ")"));
        return all.stream().filter(pr -> filter.matches(pr, now)).toList();
    }
}
