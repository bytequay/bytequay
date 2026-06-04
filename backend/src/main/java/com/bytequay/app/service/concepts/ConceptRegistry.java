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
package com.bytequay.app.service.concepts;

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scans the {@code com.bytequay.app} classpath on startup for
 * {@link Concept}-annotated classes, methods, and fields (the
 * fields case picks up enum constants like
 * {@code TaskStatus.AWAITING_REVIEW}) and exposes the resolved
 * spec list. Counterpart to
 * {@link com.bytequay.app.service.tools.AgentToolRegistry} —
 * deterministic ordering, fail-fast on conflicts within the same
 * scope, and a {@link #byName(String)} lookup that honours the
 * USER &gt; WORKSPACE &gt; REPO &gt; APP specificity rule.
 *
 * <h3>Runtime registration</h3>
 *
 * The scan only populates APP-scoped concepts. Narrower scopes
 * arrive at runtime through {@link #registerRuntime(ConceptSpec)}
 * (WORKSPACE / REPO from glossary parsing,
 * USER from the saved-views table). The runtime path may add or
 * replace entries; the most-specific scope always wins, and the
 * runners-up are kept on the {@link Alternates} record so
 * {@code lookup_term} can surface the audit trail.
 */
@Component
public class ConceptRegistry
{
    private static final Logger log = LoggerFactory.getLogger(ConceptRegistry.class);

    /** Base package the classpath scan walks. Hard-coding it
     *  (rather than reading from configuration) keeps the
     *  registry self-contained — the project owns this package
     *  prefix and changing it is a coordinated rename anyway. */
    private static final String BASE_PACKAGE = "com.bytequay.app";

    /** All registered specs, keyed by {@code name → scope → spec}.
     *  Insertion-ordered inner map so the alternates list returned
     *  by {@link #alternatesFor} is stable. */
    private final Map<String, Map<ConceptScope, ConceptSpec>> byNameThenScope = new LinkedHashMap<>();

    /** Cached sorted view of all (name, winning scope) specs, used
     *  by {@link #list(ConceptKind)}. Rebuilt on every mutation —
     *  the catalog only changes on startup + occasional re-syncs
     *  so the rebuild cost is irrelevant. */
    private List<ConceptSpec> sorted = List.of();

    @EventListener(ContextRefreshedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void scan()
            throws IOException
    {
        // Idempotent guard — ContextRefreshedEvent can fire more
        // than once in some test setups.
        if (!byNameThenScope.isEmpty()) {
            return;
        }
        Set<Class<?>> classes = findCandidateClasses();
        for (Class<?> c : classes) {
            collectFrom(c);
        }
        rebuildSorted();
        log.info("ConceptRegistry: registered {} concept(s): {}",
                sorted.size(),
                sorted.stream().map(ConceptSpec::name).toList());
    }

    /**
     * Register a runtime-scoped concept (WORKSPACE / REPO / USER).
     * Replaces any prior spec at the same scope for the same name;
     * APP-scoped specs from the startup scan are never overwritten.
     */
    public synchronized void registerRuntime(ConceptSpec spec)
    {
        if (spec.scope() == ConceptScope.APP) {
            throw new IllegalArgumentException(
                    "APP scope is reserved for code-anchored concepts: " + spec.name());
        }
        byNameThenScope
                .computeIfAbsent(spec.name(), n -> new EnumMap<>(ConceptScope.class))
                .put(spec.scope(), spec);
        rebuildSorted();
    }

    /**
     * Drop every spec at a given scope. Used by the glossary parser
     * to clear a workspace's prior entries before re-loading.
     */
    public synchronized void clearScope(ConceptScope scope)
    {
        if (scope == ConceptScope.APP) {
            throw new IllegalArgumentException("APP scope is not clearable");
        }
        byNameThenScope.values().forEach(perScope -> perScope.remove(scope));
        byNameThenScope.values().removeIf(Map::isEmpty);
        rebuildSorted();
    }

    /** All winning specs, sorted by name. Stable across calls so
     *  the {@code list_terms} manifest stays prefix-cache-friendly. */
    public List<ConceptSpec> all()
    {
        return sorted;
    }

    /** Subset filtered by kind, same stable ordering. */
    public List<ConceptSpec> list(ConceptKind kind)
    {
        if (kind == null) {
            return sorted;
        }
        return sorted.stream().filter(s -> s.kind() == kind).toList();
    }

    /** Returns the most-specific spec for {@code name} (USER &gt;
     *  WORKSPACE &gt; REPO &gt; APP), or {@link Optional#empty()}
     *  if no spec is registered under any scope. */
    public Optional<ConceptSpec> byName(String name)
    {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        Map<ConceptScope, ConceptSpec> perScope = byNameThenScope.get(name);
        if (perScope == null || perScope.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pickWinner(perScope));
    }

    /** Full lookup view: the winning spec plus any alternates
     *  (other scopes' specs for the same name, ordered USER &gt;
     *  WORKSPACE &gt; REPO &gt; APP and excluding the winner).
     *  {@code lookup_term} returns this so the resolution stays
     *  auditable. */
    public Optional<Alternates> lookup(String name)
    {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        Map<ConceptScope, ConceptSpec> perScope = byNameThenScope.get(name);
        if (perScope == null || perScope.isEmpty()) {
            return Optional.empty();
        }
        ConceptSpec winner = pickWinner(perScope);
        List<ConceptSpec> alternates = perScope.values().stream()
                .filter(s -> s != winner)
                .sorted(Comparator.comparing(ConceptSpec::scope))
                .toList();
        return Optional.of(new Alternates(winner, alternates));
    }

    /** Resolved spec + the other-scope candidates for the same
     *  name. */
    public record Alternates(ConceptSpec winner, List<ConceptSpec> alternates) {}

    // ── Internals ───────────────────────────────────────────────

    private static ConceptSpec pickWinner(Map<ConceptScope, ConceptSpec> perScope)
    {
        for (ConceptScope scope : new ConceptScope[] {
                ConceptScope.USER, ConceptScope.WORKSPACE,
                ConceptScope.REPO, ConceptScope.APP}) {
            ConceptSpec candidate = perScope.get(scope);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private synchronized void rebuildSorted()
    {
        List<ConceptSpec> winners = byNameThenScope.values().stream()
                .map(ConceptRegistry::pickWinner)
                .filter(s -> s != null)
                .sorted(Comparator.comparing(ConceptSpec::name))
                .collect(Collectors.toList());
        this.sorted = ImmutableList.copyOf(winners);
    }

    /** Walk the configured classpath looking for {@code .class}
     *  files under {@link #BASE_PACKAGE}. Loaded eagerly so the
     *  registry can reflect on each class's methods and fields;
     *  the up-front cost is a few hundred small classes — paid
     *  once at startup — and is negligible next to Spring's own
     *  bean scan. */
    private static Set<Class<?>> findCandidateClasses()
            throws IOException
    {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        Resource[] resources = resolver.getResources(
                "classpath*:" + BASE_PACKAGE.replace('.', '/') + "/**/*.class");
        Set<Class<?>> classes = new HashSet<>();
        for (Resource r : resources) {
            try {
                MetadataReader meta = readers.getMetadataReader(r);
                String className = meta.getClassMetadata().getClassName();
                classes.add(Class.forName(className, false,
                        Thread.currentThread().getContextClassLoader()));
            }
            catch (ClassNotFoundException | LinkageError e) {
                // A class that can't be loaded (optional dep
                // missing, generated stub) can't carry a concept
                // anyway — skip rather than block startup.
                log.debug("ConceptRegistry: skipping unloadable class {}: {}",
                        r.getDescription(), e.toString());
            }
        }
        return classes;
    }

    private synchronized void collectFrom(Class<?> type)
    {
        Concept onClass = type.getAnnotation(Concept.class);
        if (onClass != null) {
            register(buildSpec(onClass, type.getName()));
        }
        for (Method method : type.getDeclaredMethods()) {
            Concept onMethod = method.getAnnotation(Concept.class);
            if (onMethod != null) {
                register(buildSpec(onMethod, type.getName() + "#" + method.getName()));
            }
        }
        for (Field field : type.getDeclaredFields()) {
            Concept onField = field.getAnnotation(Concept.class);
            if (onField != null) {
                register(buildSpec(onField, type.getName() + "#" + field.getName()));
            }
        }
    }

    private void register(ConceptSpec spec)
    {
        Map<ConceptScope, ConceptSpec> perScope =
                byNameThenScope.computeIfAbsent(spec.name(), n -> new EnumMap<>(ConceptScope.class));
        ConceptSpec prior = perScope.put(spec.scope(), spec);
        if (prior != null) {
            // Two code sites annotated with the same name at the
            // same scope: fail fast so the duplicate is fixed up
            // front rather than letting one silently win.
            throw new IllegalStateException(
                    "duplicate @Concept(name=\"" + spec.name() + "\") at scope "
                            + spec.scope() + ": " + prior.source() + " and " + spec.source());
        }
    }

    private static ConceptSpec buildSpec(Concept ann, String source)
    {
        return new ConceptSpec(
                ann.name(),
                Arrays.asList(ann.aka()),
                ann.kind(),
                ann.definition(),
                Arrays.asList(ann.examples()),
                Arrays.asList(ann.relatedTools()),
                Arrays.asList(ann.relatedConcepts()),
                ann.scope(),
                source);
    }

    /** Returns the alternates for {@code name} in resolution order,
     *  excluding the winning scope. Exposed for tests; callers
     *  should use {@link #lookup(String)} which bundles winner +
     *  alternates. */
    List<ConceptSpec> alternatesFor(String name)
    {
        return lookup(name).map(Alternates::alternates).orElse(List.of());
    }
}
