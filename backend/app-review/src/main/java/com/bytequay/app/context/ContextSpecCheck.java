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

import com.bytequay.core.context.ContextLimits;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The latest spec check of one directory: the pages it read to find the specs the code implements, the specs it checked against,
 * the candidate gaps its check turn found, and what reproducing each with a test showed. Only a confirmed candidate is a finding. A
 * later check of the directory keeps the findings.
 *
 * @param specs the pages the user kept from the directory's links, and the ones they added
 * @param maxReproductions how many candidates the user let this check reproduce, most severe first; each is a build
 */
public record ContextSpecCheck(
        String id, String directory, String baselineSha, String requestKey,
        List<String> specs, List<String> checkedAgainst, List<String> labels, State state, List<Candidate> candidates, List<String> covered, List<String> next,
        int reproductions, int maxReproductions, Optional<String> detail, Instant startedAt, Optional<Instant> endedAt)
{
    /** The pages a check may read as specs: every page the directory's code links, and the ones the user adds; with none, it proposes its own. */
    public static final int MAX_SPECS = 30;
    /** A check may also propose this many specifications the code implements without linking them, after reading them. */
    public static final int MAX_PROPOSED = 2;
    public static final int MAX_CANDIDATES = 30;
    /** The most candidates a check may reproduce; the user picks this or fewer for each check. */
    public static final int MAX_REPRODUCTIONS = 8;

    public ContextSpecCheck
    {
        ContextLimits.text(id, "check id", 100);
        ContextLimits.pathPrefix(directory);
        checkArgument(baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid check baseline");
        ContextLimits.text(requestKey, "requestKey", 90);
        specs = ImmutableList.copyOf(specs);
        ContextLimits.unique(specs, "specs", MAX_SPECS);
        specs.forEach(ContextLimits::documentationUrl);
        checkedAgainst = ImmutableList.copyOf(checkedAgainst);
        ContextLimits.unique(checkedAgainst, "checked specs", MAX_SPECS);
        checkedAgainst.forEach(ContextLimits::documentationUrl);
        labels = ImmutableList.copyOf(labels);
        ContextLimits.unique(labels, "labels", 300);
        requireNonNull(state, "state is null");
        candidates = ImmutableList.copyOf(candidates);
        checkArgument(candidates.size() <= MAX_CANDIDATES, "A spec check keeps at most %s candidates", MAX_CANDIDATES);
        checkArgument(candidates.stream().map(Candidate::id).distinct().count() == candidates.size(), "Candidate ids repeat");
        covered = ImmutableList.copyOf(covered);
        next = ImmutableList.copyOf(next);
        checkArgument(covered.size() <= 100 && next.size() <= 100, "Too many sections");
        covered.forEach(section -> ContextLimits.text(section, "covered section", 200));
        next.forEach(section -> ContextLimits.text(section, "next section", 200));
        checkArgument(maxReproductions >= 1 && maxReproductions <= MAX_REPRODUCTIONS, "A check reproduces 1 to %s candidates", MAX_REPRODUCTIONS);
        checkArgument(reproductions >= 0 && reproductions <= maxReproductions, "Invalid reproduction count");
        requireNonNull(detail, "detail is null");
        detail.ifPresent(value -> ContextLimits.text(value, "check detail", 1000));
        requireNonNull(startedAt, "startedAt is null");
        requireNonNull(endedAt, "endedAt is null");
        checkArgument(state.active() == endedAt.isEmpty(), "Only an ended check has an end time");
    }

    public enum State
    {
        CHECKING, REPRODUCING, DONE, STOPPED, FAILED;

        public boolean active()
        {
            return this == CHECKING || this == REPRODUCING;
        }
    }

    /** Candidates the check did not reach end as not reproduced, saying why, so the report never shows them as findings. */
    public ContextSpecCheck ended(State replacement, Optional<String> why, Instant at)
    {
        checkArgument(!replacement.active(), "An ended check has a final state");
        String reason = switch (replacement) {
            case DONE -> "Not reached: this check reproduces at most " + maxReproductions + (maxReproductions == 1 ? " candidate" : " candidates") + ", most severe first";
            case STOPPED -> "The check was stopped before reproducing it";
            default -> "The check failed before reproducing it";
        };
        List<Candidate> settled = candidates.stream().map(candidate -> candidate.status() == Status.PENDING || candidate.status() == Status.REPRODUCING
                ? candidate.withResult(Status.NOT_REPRODUCED, candidate.test(), Optional.of(reason), Optional.empty()) : candidate).toList();
        return new ContextSpecCheck(id, directory, baselineSha, requestKey, specs, checkedAgainst, labels, replacement, settled,
                covered, next, reproductions, maxReproductions, why, startedAt, Optional.of(at));
    }

    public ContextSpecCheck reproducing(Candidate candidate)
    {
        return new ContextSpecCheck(id, directory, baselineSha, requestKey, specs, checkedAgainst, labels, State.REPRODUCING,
                replace(candidate.withResult(Status.REPRODUCING, Optional.empty(), Optional.empty(), Optional.empty())), covered, next,
                reproductions + 1, maxReproductions, detail, startedAt, endedAt);
    }

    public ContextSpecCheck withCandidates(List<Candidate> replacement, List<String> checked, List<String> coveredSections, List<String> nextSections)
    {
        return new ContextSpecCheck(id, directory, baselineSha, requestKey, specs, checked, labels, state, replacement,
                coveredSections, nextSections, reproductions, maxReproductions, detail, startedAt, endedAt);
    }

    public ContextSpecCheck withCandidate(Candidate replacement)
    {
        return withCandidates(replace(replacement), checkedAgainst, covered, next);
    }

    private List<Candidate> replace(Candidate replacement)
    {
        checkArgument(candidates.stream().anyMatch(candidate -> candidate.id().equals(replacement.id())), "Unknown spec check candidate: %s", replacement.id());
        return candidates.stream().map(candidate -> candidate.id().equals(replacement.id()) ? replacement : candidate).toList();
    }

    public Candidate candidate(String candidateId)
    {
        return candidates.stream().filter(candidate -> candidate.id().equals(candidateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown spec check candidate: " + candidateId));
    }

    /** The next candidate to reproduce: most severe first, then in the order found; never one an open issue already reports. */
    public Optional<Candidate> nextToReproduce()
    {
        if (reproductions >= maxReproductions) {
            return Optional.empty();
        }
        return candidates.stream().filter(candidate -> candidate.status() == Status.PENDING)
                .min(Comparator.comparingInt(Candidate::severity).reversed().thenComparingInt(candidates::indexOf));
    }

    /**
     * A new check of the directory keeps the earlier check's findings, refuted rules and already reported gaps, so it neither repeats nor
     * loses them; unfinished and unreproduced candidates are dropped for the new check to find again.
     */
    public List<Candidate> kept()
    {
        return candidates.stream().filter(candidate -> candidate.status() == Status.CONFIRMED || candidate.status() == Status.REFUTED
                || candidate.status() == Status.ALREADY_REPORTED).toList();
    }

    public enum Type
    {
        BUG, FEATURE_REQUEST, IMPROVEMENT
    }

    /** How the check turn predicts the code treats a spec rule; the prediction decides the kind of issue a confirmed gap files. */
    public enum Predicted
    {
        DIVERGES(Type.BUG), IGNORES(Type.BUG), REFUSES(Type.FEATURE_REQUEST), REFUSES_UNCLEARLY(Type.IMPROVEMENT), SKIPS_SHOULD(Type.IMPROVEMENT);

        private final Type type;

        Predicted(Type type)
        {
            this.type = type;
        }

        public Type type()
        {
            return type;
        }
    }

    public enum Status
    {
        PENDING, REPRODUCING, CONFIRMED, REFUTED, NOT_REPRODUCED, ALREADY_REPORTED
    }

    /**
     * One gap the check turn found. Its test, reason and draft come from the reproduction turn; ByteQuay took the test command's exit
     * code and output from the provider's recorded events, not from the agent.
     */
    public record Candidate(
            String id, Type type, Predicted predicted, String title, String rule, String specUrl, List<String> code, String reasoning, int severity,
            Optional<Duplicate> duplicate, Status status, Optional<Test> test, Optional<String> reason, Optional<Draft> draft, Optional<String> dismissed)
    {
        public Candidate
        {
            ContextLimits.text(id, "candidate id", 60);
            requireNonNull(type, "type is null");
            requireNonNull(predicted, "predicted is null");
            checkArgument(type == predicted.type(), "The predicted outcome decides the candidate type");
            ContextLimits.text(title, "candidate title", 200);
            ContextLimits.text(rule, "spec rule", 2000);
            ContextLimits.text(specUrl, "spec link", 1400);
            code = ImmutableList.copyOf(code);
            checkArgument(!code.isEmpty() && code.size() <= 10, "A candidate cites one to ten code locations");
            code.forEach(location -> ContextLimits.text(location, "code location", 1100));
            ContextLimits.text(reasoning, "candidate reasoning", 2000);
            checkArgument(severity >= 1 && severity <= 3, "Severity is 1, 2 or 3");
            requireNonNull(duplicate, "duplicate is null");
            requireNonNull(status, "status is null");
            requireNonNull(test, "test is null");
            requireNonNull(reason, "reason is null");
            reason.ifPresent(value -> ContextLimits.text(value, "result reason", 2000));
            requireNonNull(draft, "draft is null");
            checkArgument(draft.isEmpty() || status == Status.CONFIRMED, "Only a confirmed gap has an issue draft");
            requireNonNull(dismissed, "dismissed is null");
            dismissed.ifPresent(value -> ContextLimits.text(value, "dismissal", 500));
        }

        public Candidate withResult(Status replacement, Optional<Test> ran, Optional<String> why, Optional<Draft> drafted)
        {
            return new Candidate(id, type, predicted, title, rule, specUrl, code, reasoning, severity, duplicate, replacement, ran, why, drafted, dismissed);
        }

        public Candidate withDismissed(String why)
        {
            return new Candidate(id, type, predicted, title, rule, specUrl, code, reasoning, severity, duplicate, status, test, reason, draft, Optional.of(why));
        }
    }

    /** An existing issue or pull request that may already report the gap. */
    public record Duplicate(int number, String title, String state, String url)
    {
        public Duplicate
        {
            checkArgument(number > 0, "Invalid issue number");
            ContextLimits.text(title, "issue title", 500);
            ContextLimits.text(state, "issue state", 20);
            ContextLimits.text(url, "issue url", 500);
        }

        public boolean open()
        {
            return state.equals("open");
        }
    }

    /** The reproducing test as ByteQuay recorded it: the file, the command the provider ran, its exit code and the end of its output. */
    public record Test(String path, String command, int exitCode, String output, String source)
    {
        public static final int MAX_OUTPUT_BYTES = 8 * 1024;
        public static final int MAX_SOURCE_BYTES = 12 * 1024;

        public Test
        {
            ContextLimits.text(path, "test path", 1000);
            ContextLimits.text(command, "test command", 2000);
            checkArgument(ContextLimits.bytes(output) <= MAX_OUTPUT_BYTES, "Test output excerpt exceeds 8 KiB");
            checkArgument(ContextLimits.bytes(source) <= MAX_SOURCE_BYTES, "Test source excerpt exceeds 12 KiB");
        }
    }

    /** An issue the user can file: title, Markdown body and the repository labels that fit. */
    public record Draft(String title, String body, List<String> labels)
    {
        public static final int MAX_BODY_BYTES = 32 * 1024;

        public Draft
        {
            ContextLimits.text(title, "issue title", 256);
            ContextLimits.text(body, "issue body", MAX_BODY_BYTES);
            labels = ImmutableList.copyOf(labels);
            ContextLimits.unique(labels, "labels", 10);
            labels.forEach(label -> ContextLimits.text(label, "label", 100));
        }
    }
}
