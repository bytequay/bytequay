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

/**
 * Provenance / specificity of a {@link Concept}. The conflict rule
 * on duplicate names is "most-specific wins":
 * {@link #USER} &gt; {@link #WORKSPACE} &gt; {@link #REPO} &gt;
 * {@link #APP}, with the runners-up carried as alternates on a
 * {@code lookup_term} response so the resolution stays auditable.
 *
 * <p>Code-anchored concepts ({@code @Concept} on a class / method /
 * enum constant) are always {@link #APP}-scoped. The narrower
 * scopes are populated at runtime — WORKSPACE / REPO from glossary
 * sections inside the brain {@code .md} files, USER from the
 * Saved Views settings surface.
 */
public enum ConceptScope
{
    /** Built-in concept declared with {@code @Concept} on app code. */
    APP,

    /** Concept defined under a {@code ## Glossary} section of a
     *  repo's {@code REPO.md} brain file. */
    REPO,

    /** Concept defined under a {@code ## Glossary} section of a
     *  workspace's {@code WORKSPACE.md} brain file. */
    WORKSPACE,

    /** Concept the user authored via the Saved Views settings
     *  surface; persisted in the {@code concept_user} table. */
    USER
}
