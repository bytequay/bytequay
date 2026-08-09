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

import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PullRequestDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNull;

/**
 * Deterministic PR ranking. Two passes:
 *
 * <ul>
 *   <li>{@link #preRank(RepoPrSource)} — a cheap score from catalog metadata
 *       alone (size focus, mechanical/bot penalties), used to choose which
 *       PRs are worth fetching evidence for.</li>
 *   <li>{@link #refine(RepoPrSource, PrEvidenceBundle, List)} — the full score
 *       from the fetched evidence: review-outcome/change linkage first,
 *       comment volume barely at all. A behavioural review whose concern the
 *       author actually fixed outranks both a mechanical PR and a comment-only
 *       debate.</li>
 * </ul>
 *
 * No model calls — every signal is computed from catalog rows and the
 * snapshot-pinned bundle.
 */
@Component
public class PrPriorityScorer
{
    private static final Set<String> MECHANICAL_LABELS = ImmutableSet.of(
            "dependencies", "mechanical", "automated", "style", "formatting", "chore");
    private static final Set<String> BOT_LOGINS = ImmutableSet.of(
            "dependabot[bot]", "renovate[bot]", "github-actions[bot]");

    private final ObjectMapper json;

    public PrPriorityScorer(ObjectMapper json)
    {
        this.json = requireNonNull(json, "json is null");
    }

    /** Cheap metadata-only pre-rank for candidate selection. */
    public double preRank(RepoPrSource source)
    {
        JsonNode meta = metadata(source);
        double score = 1.0;
        if (isMechanical(meta)) {
            score -= 2.0;
        }
        int churn = meta.path("additions").asInt(0) + meta.path("deletions").asInt(0);
        if (churn >= 1 && churn <= 400) {
            score += 0.5;                       // small, reviewable, likely intentional
        }
        else if (churn > 3000) {
            score -= 1.0;                       // bulk change, usually mechanical
        }
        return score;
    }

    /** Full evidence-backed score. Ranks outcome/change linkage, not comments. */
    public double refine(RepoPrSource source, PrEvidenceBundle bundle, List<OutcomeChain> chains)
    {
        JsonNode meta = metadata(source);
        double score = 0.0;

        // Review-outcome / current-code linkage — the dominant signal.
        int linkage = chains.stream().mapToInt(OutcomeChain::depth).sum();
        long addressedResolved = chains.stream()
                .filter(c -> c.addressed() && c.resolved()).count();
        score += 2.0 * linkage;
        score += 2.0 * addressedResolved;

        // Substantive review engagement (a verdict, not just chatter).
        long substantiveReviewers = safe(bundle.reviews()).stream()
                .filter(r -> "CHANGES_REQUESTED".equalsIgnoreCase(r.state())
                        || "APPROVED".equalsIgnoreCase(r.state()))
                .map(PrReviewState::login)
                .filter(l -> l != null)
                .distinct()
                .count();
        score += 0.5 * substantiveReviewers;

        // Regression/test signal.
        long testFiles = safe(bundle.files()).stream()
                .map(PullRequestDetail.ChangedFile::filename)
                .filter(PrPriorityScorer::looksLikeTest)
                .count();
        score += 0.3 * Math.min(testFiles, 3);

        // Comment volume barely counts — a naming debate must not win on it.
        int comments = meta.path("commentCount").asInt(0);
        score += 0.01 * Math.min(comments, 50);

        // Mechanical/noise penalty and staleness-ish size penalty.
        if (isMechanical(meta)) {
            score -= 3.0;
        }
        int churn = meta.path("additions").asInt(0) + meta.path("deletions").asInt(0);
        if (churn >= 1 && churn <= 400) {
            score += 0.3;
        }
        else if (churn > 3000) {
            score -= 0.5;
        }
        return score;
    }

    private boolean isMechanical(JsonNode meta)
    {
        String author = meta.path("author").asText("").toLowerCase(Locale.ROOT);
        if (author.endsWith("[bot]") || BOT_LOGINS.contains(author)) {
            return true;
        }
        String title = meta.path("title").asText("");
        if (title.startsWith("Bump ") || title.startsWith("build(deps")) {
            return true;
        }
        Set<String> labels = labels(meta);
        return labels.stream().anyMatch(MECHANICAL_LABELS::contains);
    }

    private Set<String> labels(JsonNode meta)
    {
        JsonNode node = meta.path("labels");
        if (!node.isArray()) {
            return ImmutableSet.of();
        }
        return StreamSupport.stream(node.spliterator(), false)
                .map(n -> n.asText("").toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private JsonNode metadata(RepoPrSource source)
    {
        try {
            String raw = source.metadataJson();
            return raw == null || raw.isBlank()
                    ? json.createObjectNode() : json.readTree(raw);
        }
        catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static boolean looksLikeTest(String path)
    {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/tests/")
                || lower.contains("test") && (lower.endsWith(".java") || lower.endsWith(".ts")
                        || lower.endsWith(".js") || lower.endsWith(".py") || lower.endsWith(".go"))
                || lower.endsWith(".spec.ts") || lower.endsWith(".spec.js");
    }

    private static <T> List<T> safe(List<T> list)
    {
        return list == null ? List.of() : list;
    }
}
