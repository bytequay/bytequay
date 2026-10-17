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
package com.bytequay.agent.spi;

/**
 * What a session may do with the checkout it is rooted at.
 *
 * <p>This is the containment axis, separate from {@link AgentPermissionPolicy}, which decides
 * how far a permitted action may reach. A session has exactly one access level, so the levels
 * cannot be combined by accident at a construction site.
 */
public enum SessionAccess
{
    /** Reads and changes its worktree, under the session's permission policy. */
    FULL,
    /**
     * Reads the repository it is rooted at and never changes it. The tool set withholds
     * every editing tool and the provider sandbox denies writes to the worktree, so the
     * boundary survives a tool that is auto-approved.
     */
    READ_ONLY,
    /** Reads a shared checkout without edits, while retaining the owning reviewer's report tools. */
    REVIEW_READ_ONLY,
    /**
     * No tools at all. Such a session has nothing to read a repository with, so
     * {@link AgentSessionContext} confines its worktree to the runtime directory.
     */
    TEXT_ONLY,
    /**
     * No file, shell or web tools: only the ByteQuay tools the session was given.
     * The provider session persists, so a conversation can resume. Like TEXT_ONLY,
     * it has no checkout; its folder is an empty one ByteQuay owns.
     */
    TOOLS_ONLY;

    /** Whether this level withholds the capability to change the worktree. */
    public boolean restricted()
    {
        return this != FULL;
    }
}
