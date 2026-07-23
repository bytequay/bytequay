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

import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.service.codegraph.CodeGraphResult;
import com.bytequay.app.service.codegraph.CodeGraphUpdateCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Maps a bundle's changed files to the <em>current</em> modules/symbols/tests
 * at the pinned checkout, emitting stable {@link PrEvidenceBundle.EvidenceRef}s
 * all pinned to the default-branch snapshot ({@code repoSha}).
 *
 * <p>Uses a fresh CodeGraph when it is available, and falls back to git paths
 * and exact source reads when it is not — so a CodeGraph-unavailable run still
 * yields a path-based bundle rather than nothing. Every ref is pinned to
 * {@code repoSha}, never a SHA past the snapshot.
 */
@Component
public class EvidenceCodeGraphMapper
{
    private static final Logger log = LoggerFactory.getLogger(EvidenceCodeGraphMapper.class);

    private final CodeGraphUpdateCoordinator codeGraph;

    public EvidenceCodeGraphMapper(CodeGraphUpdateCoordinator codeGraph)
    {
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
    }

    /**
     * Attach current-code refs for every changed file. When {@code checkout}
     * is null (no verified clone) or CodeGraph is unavailable, still emits a
     * path-based ref per file so the bundle is never empty.
     */
    public List<PrEvidenceBundle.EvidenceRef> attach(
            PrEvidenceBundle bundle, Path checkout, String repoSha)
    {
        boolean graphFresh = ensureGraph(checkout);
        List<PrEvidenceBundle.EvidenceRef> refs = new ArrayList<>();
        for (PullRequestDetail.ChangedFile file : safe(bundle.files())) {
            String path = file.filename();
            if (path == null) {
                continue;
            }
            boolean present = checkout != null && Files.exists(checkout.resolve(path));
            // Always emit the path ref — this is the path-based bundle that
            // survives CodeGraph being unavailable. Pinned to repoSha.
            refs.add(new PrEvidenceBundle.EvidenceRef(
                    looksLikeTest(path) ? "test" : "file",
                    null, null, repoSha, path, null, null,
                    MergedPrCatalog.sha256(repoSha + "|" + path + "|present=" + present)));

            if (graphFresh && present) {
                symbolRef(checkout, path, repoSha).ifPresent(refs::add);
            }
        }
        return refs;
    }

    /** Freshen the graph; false on disabled / skipped / error (fall back). */
    private boolean ensureGraph(Path checkout)
    {
        if (checkout == null) {
            return false;
        }
        try {
            CodeGraphResult result = codeGraph.ensureFreshSync(checkout, "phase2-evidence-mapping");
            return result.ok() && !result.skipped();
        }
        catch (RuntimeException e) {
            log.debug("CodeGraph freshen failed for {}: {}", checkout, e.getMessage());
            return false;
        }
    }

    private Optional<PrEvidenceBundle.EvidenceRef> symbolRef(
            Path checkout, String path, String repoSha)
    {
        try {
            String out = codeGraph.explore(checkout, "symbols defined in " + path);
            if (out == null || out.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new PrEvidenceBundle.EvidenceRef(
                    "symbol", null, null, repoSha, path, null, null,
                    MergedPrCatalog.sha256(out)));
        }
        catch (Exception e) {
            // Path ref already covers this file; symbols are best-effort.
            return Optional.empty();
        }
    }

    private static boolean looksLikeTest(String path)
    {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/tests/")
                || lower.endsWith(".spec.ts") || lower.endsWith(".spec.js")
                || (lower.contains("test") && lower.endsWith(".java"));
    }

    private static <T> List<T> safe(List<T> list)
    {
        return list == null ? List.of() : list;
    }
}
