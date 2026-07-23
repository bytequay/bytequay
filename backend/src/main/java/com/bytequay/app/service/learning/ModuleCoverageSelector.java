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
package com.bytequay.app.service.learning;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks a module-covering batch of ranked PRs. A greedy two-pass selection:
 * first take the highest-scoring PR that reaches an as-yet-uncovered module
 * (so one hot module can't crowd the batch), then fill any remaining slots by
 * score alone. Fully deterministic — ties break on PR number.
 */
@Component
public class ModuleCoverageSelector
{
    /** A ranked candidate and the modules it touches (may be empty). */
    public record Candidate(int prNumber, double score, Set<String> modules) {}

    public List<Integer> select(List<Candidate> candidates, int limit)
    {
        if (limit <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparingInt(Candidate::prNumber))
                .toList();

        LinkedHashSet<Integer> chosen = new LinkedHashSet<>();
        Set<String> covered = new HashSet<>();

        // Pass 1: prefer PRs that widen module coverage.
        for (Candidate c : ranked) {
            if (chosen.size() >= limit) {
                break;
            }
            boolean widensCoverage = c.modules().stream().anyMatch(m -> !covered.contains(m));
            if (widensCoverage) {
                chosen.add(c.prNumber());
                covered.addAll(c.modules());
            }
        }

        // Pass 2: fill the rest by score, coverage already satisfied.
        for (Candidate c : ranked) {
            if (chosen.size() >= limit) {
                break;
            }
            chosen.add(c.prNumber());
        }
        return List.copyOf(chosen);
    }
}
