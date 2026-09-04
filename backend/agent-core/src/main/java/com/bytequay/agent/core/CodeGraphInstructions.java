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

/** ByteQuay's managed guidance for the CodeGraph tool; delivered through a provider's instruction channel, never a repository file. */
public final class CodeGraphInstructions
{
    public static final String MANAGED = """
            ByteQuay provides an optional CodeGraph for the current task worktree. Use \
            codegraph_explore when callers, callees, implementations, routes, dependencies, or \
            cross-file structure would help. You decide whether and when it is useful. ByteQuay \
            keeps the index current before every query, including your own uncommitted edits, so \
            there is nothing to refresh. The first call in a task may take longer while the index \
            is prepared. Graph results are navigation help; inspect current source and use \
            compiler, test, linter, or runtime evidence when correctness matters. The task does not \
            depend on CodeGraph. If the tool fails, continue with normal source and search tools. Do \
            not install, configure, or upgrade CodeGraph yourself, invoke its CLI directly, or \
            provide repository paths to it; use only the managed tool.""";

    private CodeGraphInstructions() {}
}
