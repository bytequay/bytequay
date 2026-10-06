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
package com.bytequay.agent.core;

/** Managed guidance for the bounded IDE query tool; never written into repository files. */
public final class CodeQueryInstructions
{
    public static final String MANAGED = """
            ByteQuay provides code_query for Java, Go and Rust in this task's own working copy. \
            It shares the IDE's language services. Use SYMBOLS for a name search, DOCUMENT_SYMBOLS \
            for an outline, or DEFINITIONS, REFERENCES, IMPLEMENTATIONS, INCOMING_CALLS and \
            OUTGOING_CALLS at a source position. DIAGNOSTICS reads currently published diagnostics; \
            no diagnostics is not proof of a successful build or test. Always name a repository-relative \
            source path to activate its language. Positions and ranges use one-based lines and UTF-16 \
            columns. Read current source before choosing a position. Supply a limit from 1 to 200. \
            SYMBOLS also requires query; positional operations require line and column. \
            Results include task, revision and working-copy fingerprint. They cover imported projects \
            and can be partial while indexing. Check status and truncation, inspect the actual source, \
            and use compiler/test/runtime evidence for correctness. Stale or unsaved results must be \
            retried after the source is reconciled. If unavailable or unsupported, continue with normal \
            source and text search; do not install or start another index. This is not natural-language \
            architecture search or a repository dependency graph. Use ordinary source search for those \
            questions and never describe text matches as semantic callers.""";

    private CodeQueryInstructions() {}
}
