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
package com.bytequay.app.service.harness;

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Read-only GitHub Actions probe over the existing strict Checks API client. */
@Component
public class GitHubActionsProbe
{
    // The whole log is kept. It used to be cut to its last 200 KB, which on any
    // suite that logs through its own teardown threw away the failures: surefire
    // prints each failing test's stack where it happened, thousands of lines
    // before the tail, and the parser cannot find a section that is not there.
    // The fetch caps at 8 MiB, so that is the real bound.
    // ponytail: cached as raw text; store the parsed evidence instead if the
    // cache table ever grows enough to matter.
    private static final Pattern RUN_ID = Pattern.compile("/actions/runs/(\\d+)");
    /** The per-commit job this repository runs to keep history bisectable. */
    private static final Pattern CHECK_COMMIT =
            Pattern.compile("check-commit \\(([0-9a-fA-F]{7,40})\\)");

    private final PullRequestRepository github;
    private final PatResolver pats;
    private final HarnessStore store;

    public GitHubActionsProbe(PullRequestRepository github, PatResolver pats, HarnessStore store)
    {
        this.github = requireNonNull(github, "github is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.store = requireNonNull(store, "store is null");
    }

    public ProbeResult probe(Watch watch, BootstrapProfile profile)
    {
        return probe(watch, profile, Map.of());
    }

    /**
     * @param fixupBySupersededSha commit sha to the sha of the {@code fixup!}
     *         that immediately follows it on the branch. A per-commit check that
     *         failed on a commit whose fixup then passed is not a defect: the
     *         two are one commit once the branch is autosquashed, so the red is
     *         an artifact of history that has not been rewritten yet. Such a
     *         check is dropped here rather than downstream, so the board can
     *         still be green and no round is spent diagnosing it.
     */
    public ProbeResult probe(
            Watch watch, BootstrapProfile profile, Map<String, String> fixupBySupersededSha)
    {
        requireNonNull(fixupBySupersededSha, "fixupBySupersededSha is null");
        String repoFullName = watch.owner() + "/" + watch.repo();
        String pat = pats.resolve(repoFullName);
        PullRequestRef ref = PullRequestRef.of(watch.owner(), watch.repo(), watch.prNumber());
        PrRawDetail detail = retry(() -> github.fetchPrDetail(pat, ref));
        if (detail == null || detail.headSha() == null || detail.headSha().isBlank()) {
            throw new IllegalStateException("GitHub did not return a PR head SHA");
        }
        List<PrCheckRunState> checks = retry(() -> github.fetchPrCheckRunsStrict(
                pat, watch.owner(), watch.repo(), detail.headSha()));
        boolean pending = checks.isEmpty();
        boolean green = !checks.isEmpty();
        List<FailedJob> failed = new ArrayList<>();
        List<String> tail = new ArrayList<>();
        Set<String> passed = passedCheckNames(checks);

        for (PrCheckRunState check : checks) {
            String name = check.name() == null ? "unnamed check" : check.name();
            String status = normalized(check.status());
            String conclusion = normalized(check.conclusion());
            tail.add(name + ": " + (conclusion.isBlank() ? status : conclusion));
            if (!"completed".equals(status)) {
                pending = true;
                green = false;
                continue;
            }
            if (profile.aggregatorJobs().contains(name)) {
                continue;
            }
            if (isSuccess(conclusion)) {
                continue;
            }
            if (supersededByAPassingFixup(name, fixupBySupersededSha, passed)) {
                // The commit this check judged no longer exists on its own: its
                // fixup is the next commit and its own check is green. The status
                // tail above still shows the red so nothing is hidden.
                continue;
            }
            green = false;
            if (!isFailure(conclusion)) {
                continue;
            }
            String log = log(pat, watch, detail.headSha(), check);
            if ("cancelled".equals(conclusion) && log.isBlank()) {
                // Cancellation is often a sibling consequence. Without a log
                // it contributes no independent failure and must not create a
                // duplicate diagnosis.
                continue;
            }
            boolean noEvidence = false;
            if (log.isBlank()) {
                log = Optional.ofNullable(check.outputSummary())
                        .filter(value -> !value.isBlank())
                        .orElseGet(() -> Optional.ofNullable(check.outputTitle())
                                .filter(value -> !value.isBlank())
                                .orElse(""));
                if (log.isBlank()) {
                    // No log and no output text: an expired artifact, external CI, or a
                    // job GitHub exposes nothing for. Surface it so the human sees the
                    // red check, but mark it undiagnosable so it defers instead of
                    // spending a model call on a placeholder string with no evidence.
                    noEvidence = true;
                    log = "CI log unavailable for " + name + " (" + conclusion + ")";
                }
            }
            failed.add(new FailedJob(
                    runId(check.htmlUrl()), check.githubId() == null ? -1 : check.githubId(),
                    name, conclusion,
                    noEvidence || profile.infraJobs().contains(name), log));
        }
        return new ProbeResult(
                detail.headSha(), detail.baseSha(), detail.headRef(), green, pending,
                Boolean.FALSE.equals(detail.mergeable()),
                List.copyOf(failed), tail(tail));
    }

    private String log(String pat, Watch watch, String headSha, PrCheckRunState check)
    {
        if (check.githubId() == null) {
            return "";
        }
        Optional<String> cached = store.cachedLog(watch.id(), headSha, check.githubId());
        if (cached.isPresent()) {
            return cached.orElseThrow();
        }
        String value = retry(() -> github.fetchCheckRunLog(
                        pat, PullRequestRef.of(watch.owner(), watch.repo(), watch.prNumber()).repoRef(),
                        check.githubId()))
                .orElse("");
        if (value.isBlank()) {
            // Nothing to cache, and caching it would be worse than nothing: the
            // key is the head SHA, so a log GitHub had not flushed yet would read
            // as a cache hit for the rest of this SHA's life and every later
            // cycle would defer the failure as undiagnosable. Costs one refetch
            // per cycle for a log that really is gone.
            return "";
        }
        store.cacheLog(watch.id(), headSha, check.githubId(), value, Instant.now().toEpochMilli());
        return value;
    }

    /**
     * True when {@code name} is the per-commit check of a commit that a passing
     * {@code fixup!} supersedes. Deliberately strict on both halves: the fixup
     * must be the very next commit, and its own per-commit check must have
     * completed successfully — a fixup still queued forgives nothing.
     */
    private static boolean supersededByAPassingFixup(
            String name, Map<String, String> fixupBySupersededSha, Set<String> passed)
    {
        Matcher matcher = CHECK_COMMIT.matcher(name);
        if (!matcher.matches()) {
            return false;
        }
        String fixup = fixupBySupersededSha.get(matcher.group(1));
        return fixup != null && passed.contains(checkCommitName(fixup));
    }

    private static Set<String> passedCheckNames(List<PrCheckRunState> checks)
    {
        Set<String> passed = new HashSet<>();
        for (PrCheckRunState check : checks) {
            if (check.name() != null
                    && "completed".equals(normalized(check.status()))
                    && isSuccess(normalized(check.conclusion()))) {
                passed.add(check.name());
            }
        }
        return passed;
    }

    private static String checkCommitName(String sha)
    {
        return "check-commit (" + sha + ")";
    }

    private static boolean isSuccess(String conclusion)
    {
        return switch (conclusion) {
            case "success", "neutral", "skipped" -> true;
            default -> false;
        };
    }

    private static boolean isFailure(String conclusion)
    {
        return switch (conclusion) {
            case "failure", "timed_out", "action_required", "cancelled" -> true;
            default -> false;
        };
    }

    private static String normalized(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String runId(String htmlUrl)
    {
        if (htmlUrl == null) {
            return null;
        }
        Matcher matcher = RUN_ID.matcher(htmlUrl);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String tail(List<String> statuses)
    {
        int from = Math.max(0, statuses.size() - 8);
        return String.join("\n", statuses.subList(from, statuses.size()));
    }

    private static <T> T retry(SupplierWithFailure<T> supplier)
    {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return supplier.get();
            }
            catch (RuntimeException e) {
                last = e;
                if (!transientFailure(e) || attempt == 2) {
                    throw e;
                }
                try {
                    Thread.sleep(100L << attempt);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("CI probe retry interrupted", interrupted);
                }
            }
        }
        throw last == null ? new IllegalStateException("CI probe failed") : last;
    }

    private static boolean transientFailure(RuntimeException failure)
    {
        if (failure instanceof ResponseStatusException response) {
            HttpStatusCode status = response.getStatusCode();
            return status.value() == 429 || status.is5xxServerError();
        }
        return failure.getCause() instanceof IOException;
    }

    /**
     * @param conflicted GitHub reports the branch no longer merges into its base —
     *         the fork's target branch moved under a run that takes days. Read off
     *         the poll we already make, so noticing costs no extra fetch.
     */
    public record ProbeResult(
            String headSha,
            String baseSha,
            String branch,
            boolean green,
            boolean pending,
            boolean conflicted,
            List<FailedJob> failedJobs,
            String runStatusTail)
    {
        public ProbeResult(
                String headSha, String baseSha, String branch, boolean green,
                boolean pending, List<FailedJob> failedJobs, String runStatusTail)
        {
            this(headSha, baseSha, branch, green, pending, false, failedJobs, runStatusTail);
        }
    }

    public record FailedJob(
            String runId,
            long checkRunId,
            String jobName,
            String conclusion,
            boolean infra,
            String log) {}

    @FunctionalInterface
    private interface SupplierWithFailure<T>
    {
        T get();
    }
}
