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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class, method, or field (including enum constants) as the
 * code-anchored definition of a domain term — the third metadata
 * axis alongside {@link com.bytequay.app.service.tools.AgentTool}
 * and the skills runtime. The {@link ConceptRegistry} scans these
 * at startup and exposes them via the {@code list_terms} /
 * {@code lookup_term} meta-tools, and (via
 * {@link com.bytequay.app.service.tools.ToolParam#enumFromConcepts()})
 * inlines their definitions into a tool param's input schema so the
 * agent reads the meaning of an enum value where it's relevant.
 *
 * <p>The leverage: when the agent asks "what's all the urgent
 * PRs?", a {@code list_prs(filter:"urgent")} call resolves
 * deterministically through the {@code @Concept} on
 * {@code UrgentPrFilter} — no per-call guessing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Concept
{
    /** Canonical (kebab-or-snake-case) name. Lookup keys and the
     *  enum values listed in {@code @ToolParam(enumFromConcepts)}
     *  match this verbatim. */
    String name();

    /** Synonyms (the things a user might call the same thing). Used
     *  by {@code lookup_term} so a hit on an alias resolves to the
     *  canonical name without the agent re-guessing. */
    String[] aka() default {};

    /** Classification — see {@link ConceptKind} for the meaning of
     *  NOUN / STATE / FILTER / VERB. */
    ConceptKind kind();

    /** One-paragraph definition. The {@code list_terms} response
     *  shows a one-line trimmed form; {@code lookup_term} returns
     *  the full text. */
    String definition();

    /** Concrete examples that disambiguate edge cases. */
    String[] examples() default {};

    /** Names of {@code @AgentTool} entries where this concept is
     *  the natural action (the back-link surfaced by
     *  {@code lookup_term} — "to operate on this, use these
     *  tools"). The registry doesn't validate these point at real
     *  tools until the meta-tools resolve them at call time. */
    String[] relatedTools() default {};

    /** Names of other concepts a reader should also look up. */
    String[] relatedConcepts() default {};

    /** Provenance — for {@code @Concept} on code this is always
     *  {@link ConceptScope#APP}. The narrower scopes are populated
     *  by glossary parsing and the saved-views table, not by this
     *  annotation. */
    ConceptScope scope() default ConceptScope.APP;
}
