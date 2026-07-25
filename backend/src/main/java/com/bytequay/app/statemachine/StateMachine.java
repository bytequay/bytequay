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
package com.bytequay.app.statemachine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Transition rules for one lifecycle axis, adapted from Trino's
 * {@code io.trino.execution.StateMachine}.
 *
 * <p>Trino's machine owns a volatile in-memory state cell because a query's
 * state lives and dies with the coordinator process. ByteQuay lifecycle
 * states (task phase and status, stage state, review-round state, turn
 * status) are durable SQLite columns mutated from many call sites, so the
 * shareable part is not the cell but the rules: the legal-edge graph, the
 * terminal set, and the transition check. Each per-entity machine
 * ({@code TaskStateMachine}, {@code StageStateMachine},
 * {@code ReviewRoundStateMachine}) supplies what Trino gets from the object
 * instance:
 *
 * <ul>
 * <li><b>current state</b> — a store read under the entity's stripe lock
 *     (the analogue of Trino's {@code synchronized (lock)});</li>
 * <li><b>the write</b> — a store update in the same transaction as the
 *     audit row;</li>
 * <li><b>change notification</b> — a Spring application event published
 *     after the guarded write (the analogue of Trino's
 *     {@code StateChangeListener}), so "when X enters state S do Y" lives
 *     in a listener, never inline in the transition.</li>
 * </ul>
 *
 * <p>Semantics mirror Trino where they can: a transition to the current
 * state is not a transition (callers treat it as a no-op, not an error),
 * and nothing ever leaves a terminal state. {@code universal} targets are
 * this codebase's addition — escape states (park, seal) reachable from
 * every non-terminal state without enumerating each edge.
 */
public final class StateMachine<S>
{
    private final String name;
    private final Map<S, Set<S>> edges;
    private final Set<S> terminalStates;
    private final Set<S> universalTargets;

    private StateMachine(String name, Map<S, Set<S>> edges, Set<S> terminalStates, Set<S> universalTargets)
    {
        this.name = requireNonNull(name, "name is null");
        this.edges = Map.copyOf(edges);
        this.terminalStates = Set.copyOf(terminalStates);
        this.universalTargets = Set.copyOf(universalTargets);
    }

    /** True when {@code from -> to} is a legal edge: an explicit forward
     *  edge, or a universal escape from any non-terminal state. A
     *  same-state "transition" and any move out of a terminal state are
     *  never legal. */
    public boolean isLegal(S from, S to)
    {
        requireNonNull(from, "from is null");
        requireNonNull(to, "to is null");
        if (from.equals(to) || isTerminal(from)) {
            return false;
        }
        if (universalTargets.contains(to)) {
            return true;
        }
        return edges.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Guard for a transition about to be applied. A same-state move is
     * allowed through as a no-op signal ({@code false}) so idempotent
     * drivers (event listener + reconciler sweeping the same edge) can
     * call it repeatedly; an illegal edge throws.
     *
     * @return true when the caller should apply the transition, false when
     *         the entity is already in {@code to}
     * @throws IllegalTransitionException on an illegal edge
     */
    public boolean checkTransition(Object entityId, S from, S to)
    {
        if (from.equals(to)) {
            return false;
        }
        if (!isLegal(from, to)) {
            throw new IllegalTransitionException(name, entityId, from, to);
        }
        return true;
    }

    public boolean isTerminal(S state)
    {
        return terminalStates.contains(requireNonNull(state, "state is null"));
    }

    /** States a non-terminal {@code from} may move to next: its explicit
     *  edges plus the universal escapes. Empty for a terminal state. */
    public Set<S> nextStates(S from)
    {
        requireNonNull(from, "from is null");
        if (isTerminal(from)) {
            return Set.of();
        }
        Set<S> next = new LinkedHashSet<>(edges.getOrDefault(from, Set.of()));
        for (S universal : universalTargets) {
            if (!universal.equals(from)) {
                next.add(universal);
            }
        }
        return Set.copyOf(next);
    }

    public String name()
    {
        return name;
    }

    public static <S> Builder<S> builder(String name)
    {
        return new Builder<>(name);
    }

    public static final class Builder<S>
    {
        private final String name;
        private final Map<S, Set<S>> edges = new HashMap<>();
        private final Set<S> terminalStates = new HashSet<>();
        private final Set<S> universalTargets = new HashSet<>();

        private Builder(String name)
        {
            this.name = requireNonNull(name, "name is null");
        }

        /** Declare the legal forward edges out of {@code from}. */
        @SafeVarargs
        public final Builder<S> edge(S from, S... to)
        {
            Set<S> targets = edges.computeIfAbsent(requireNonNull(from, "from is null"), ignored -> new LinkedHashSet<>());
            for (S target : to) {
                targets.add(requireNonNull(target, "to is null"));
            }
            return this;
        }

        /** Declare states nothing may ever leave. */
        @SafeVarargs
        public final Builder<S> terminal(S... states)
        {
            for (S state : states) {
                terminalStates.add(requireNonNull(state, "state is null"));
            }
            return this;
        }

        /** Declare escape states reachable from every non-terminal state
         *  (park, seal) without enumerating each edge. */
        @SafeVarargs
        public final Builder<S> universal(S... states)
        {
            for (S state : states) {
                universalTargets.add(requireNonNull(state, "state is null"));
            }
            return this;
        }

        public StateMachine<S> build()
        {
            for (S terminalState : terminalStates) {
                if (!edges.getOrDefault(terminalState, Set.of()).isEmpty()) {
                    throw new IllegalStateException(
                            name + " declares outgoing edges from terminal state " + terminalState);
                }
            }
            return new StateMachine<>(name, edges, terminalStates, universalTargets);
        }
    }
}
