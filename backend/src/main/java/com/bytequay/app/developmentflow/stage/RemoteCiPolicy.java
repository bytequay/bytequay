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
package com.bytequay.app.developmentflow.stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/** Deterministic evaluation of one immutable set of normalized CI checks. */
public final class RemoteCiPolicy
{
    public static final String DEFAULT_REPOSITORY_CI_POLICY_V1_SOURCE =
            "DEFAULT_REPOSITORY_CI_POLICY_V1";
    public static final Policy DEFAULT_REPOSITORY_CI_POLICY_V1 = new Policy(Map.of(
            CheckState.NONE, PolicyOutcome.WAITING,
            CheckState.MISSING, PolicyOutcome.WAITING,
            CheckState.QUEUED, PolicyOutcome.WAITING,
            CheckState.PENDING, PolicyOutcome.WAITING,
            CheckState.NEUTRAL, PolicyOutcome.FAILED,
            CheckState.SKIPPED, PolicyOutcome.ACCEPTED,
            CheckState.CANCELED, PolicyOutcome.FAILED));

    private static final Set<String> CHECK_KINDS = Set.of(
            "CHECK_RUN", "STATUS_CONTEXT", "CHECK_SUITE", "REQUIRED_MISSING");
    private static final List<CheckState> STATE_PRIORITY = List.of(
            CheckState.FAILED,
            CheckState.CANCELED,
            CheckState.MISSING,
            CheckState.PENDING,
            CheckState.QUEUED,
            CheckState.NEUTRAL,
            CheckState.SKIPPED,
            CheckState.PASSED);

    private RemoteCiPolicy() {}

    public static Evaluation evaluate(
            List<Check> observed,
            Set<String> requiredCheckNames,
            Policy policy)
    {
        requireNonNull(observed, "observed is null");
        requireNonNull(requiredCheckNames, "requiredCheckNames is null");
        requireNonNull(policy, "policy is null");

        List<Check> checks = new ArrayList<>(observed);
        Set<String> present = new HashSet<>();
        for (Check check : checks) {
            requireNonNull(check, "check is null");
            present.add(check.name());
        }
        requiredCheckNames.stream()
                .map(RemoteCiPolicy::requiredText)
                .filter(name -> !present.contains(name))
                .sorted()
                .map(name -> new Check(
                        "REQUIRED_MISSING", "missing:" + name, name,
                        CheckState.MISSING, null, null, null, null, null))
                .forEach(checks::add);

        if (checks.isEmpty()) {
            return new Evaluation(
                    CheckState.NONE, policy.outcome(CheckState.NONE),
                    List.of(), 0, 0);
        }

        PolicyOutcome aggregate = checks.stream()
                .map(check -> policy.outcome(check.state()))
                .max(Comparator.comparingInt(RemoteCiPolicy::severity))
                .orElseThrow();
        CheckState status = STATE_PRIORITY.stream()
                .filter(state -> checks.stream().anyMatch(check ->
                        check.state() == state
                                && policy.outcome(state) == aggregate))
                .findFirst()
                .orElseThrow();
        long missing = checks.stream()
                .filter(check -> check.state() == CheckState.MISSING)
                .count();
        return new Evaluation(
                status, aggregate, List.copyOf(checks), checks.size(),
                toIntExact(missing));
    }

    private static int severity(PolicyOutcome outcome)
    {
        return switch (outcome) {
            case ACCEPTED -> 0;
            case WAITING -> 1;
            case FAILED -> 2;
        };
    }

    private static String requiredText(String value)
    {
        requireNonNull(value, "required check name is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "required check name must not be blank");
        }
        return value;
    }

    public enum CheckState
    {
        NONE,
        MISSING,
        QUEUED,
        PENDING,
        PASSED,
        FAILED,
        NEUTRAL,
        SKIPPED,
        CANCELED
    }

    public enum PolicyOutcome
    {
        ACCEPTED,
        WAITING,
        FAILED
    }

    public record Policy(Map<CheckState, PolicyOutcome> configurableOutcomes)
    {
        public Policy
        {
            requireNonNull(configurableOutcomes, "configurableOutcomes is null");
            EnumMap<CheckState, PolicyOutcome> copy =
                    new EnumMap<>(CheckState.class);
            copy.putAll(configurableOutcomes);
            Set<CheckState> required = Set.of(
                    CheckState.NONE, CheckState.MISSING, CheckState.QUEUED,
                    CheckState.PENDING, CheckState.NEUTRAL,
                    CheckState.SKIPPED, CheckState.CANCELED);
            if (!copy.keySet().equals(required) || copy.containsValue(null)) {
                throw new IllegalArgumentException(
                        "CI policy must explicitly cover every configurable state");
            }
            configurableOutcomes = Map.copyOf(copy);
        }

        public PolicyOutcome outcome(CheckState state)
        {
            return switch (requireNonNull(state, "state is null")) {
                case PASSED -> PolicyOutcome.ACCEPTED;
                case FAILED -> PolicyOutcome.FAILED;
                default -> configurableOutcomes.get(state);
            };
        }
    }

    public record Check(
            String kind,
            String externalId,
            String name,
            CheckState state,
            String providerStatus,
            String providerConclusion,
            Long startedAtMs,
            Long completedAtMs,
            String rawEvidence)
    {
        public Check
        {
            kind = canonicalKind(kind);
            requiredText(externalId);
            requiredText(name);
            requireNonNull(state, "state is null");
            if (state == CheckState.NONE) {
                throw new IllegalArgumentException(
                        "NONE represents an empty check set, not a check");
            }
            if ((state == CheckState.MISSING)
                    != "REQUIRED_MISSING".equals(kind)) {
                throw new IllegalArgumentException(
                        "MISSING is reserved for synthetic required checks");
            }
            if (startedAtMs != null && completedAtMs != null
                    && completedAtMs < startedAtMs) {
                throw new IllegalArgumentException(
                        "check completion precedes its start");
            }
        }
    }

    private static String canonicalKind(String value)
    {
        String kind = requiredText(value);
        if (kind.equals("GITHUB_CHECK_RUN")) {
            return "CHECK_RUN";
        }
        if (!CHECK_KINDS.contains(kind)) {
            throw new IllegalArgumentException("unknown CI check kind " + kind);
        }
        return kind;
    }

    public record Evaluation(
            CheckState normalizedStatus,
            PolicyOutcome outcome,
            List<Check> checks,
            int checkCount,
            int missingRequiredCount)
    {
        public Evaluation
        {
            requireNonNull(normalizedStatus, "normalizedStatus is null");
            requireNonNull(outcome, "outcome is null");
            checks = List.copyOf(requireNonNull(checks, "checks is null"));
            if (checkCount != checks.size() || missingRequiredCount < 0
                    || missingRequiredCount > checkCount) {
                throw new IllegalArgumentException("CI evaluation counts are invalid");
            }
        }
    }
}
