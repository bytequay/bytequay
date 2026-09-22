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

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.stream.Collectors.joining;

/** Shared authorization rules for attended development and review, independent of provider and authentication. */
public final class AgentCommandPolicy
{
    private static final List<String> COMMON_RESTRICTIONS = ImmutableList.of(
            """
            Block an action unless its complete effects are within the authorized scope after evaluating
            the whole shell chain, expansions, substitutions, pipelines, invoked scripts, and network
            payloads. An allowed git or gh command, build, or test does not authorize surrounding commands,
            unrelated mutations, private-data transmission, credential probing, or security weakening.
            Resolve unclear targets and effects with read-only evidence before allowing the action.
            """.strip(),
            """
            Block printing, extracting, or transmitting credentials outside the service's normal
            authentication flow. Existing CLI login, credential helpers, SSH agents, and configured
            environment credentials may authenticate an authorized action without exposing their values.
            If normal authentication fails, report the missing login; do not search unrelated files,
            browser profiles, or logs for secrets.
            """.strip(),
            """
            Block persistent or reusable permission grants, blanket authorization for future actions,
            sandbox weakening, and bypassing security controls. Review the one exact proposed action
            under the existing filesystem boundary and native risk rules; native denials still apply.
            """.strip());
    private static final List<String> RESTRICTIONS = ImmutableList.<String>builder()
            .add("""
                    Block GitHub writes, including pushes, pull-request creation or updates, comments, reviews,
                    approvals, and merges, unless direct explicit user authorization covers that remote action
                    and scope. An earlier request in the same task or the user's accepted automation settings
                    supplies that authorization; do not require the user to repeat it. A general development
                    request does not authorize remote writes. Narrower user instructions, including "do not push"
                    and "keep the PR draft", prevail over automation settings.
                    """.strip(),
                    """
                    Block remote writes unless the exact destination and outgoing payload are verified to match
                    the authorized task. For Git, verify the task repository, configured remote, task branch,
                    and outgoing commits; the shell's working directory alone proves none of these. For GitHub,
                    verify the target repository and resource and the exact content or operation. Rewritten
                    history requires a single task-branch push with an exact --force-with-lease naming the
                    expected remote revision; never use an unleased force push, broad refspec, or branch deletion.
                    """.strip())
            .addAll(COMMON_RESTRICTIONS)
            .build();
    private static final List<String> REVIEW_RESTRICTIONS = ImmutableList.<String>builder()
            .add("""
                    Block creating commits, publishing changes, and moving HEAD or branch references to other commits.
                    Private review experiments must remain uncommitted. Block pushes, GitHub writes, and credential probing.
                    Allow local experiment edits, staging, unstaging, applying patches, and read-only Git metadata commands.
                    Resetting files to the current HEAD is allowed; moving HEAD or a branch to another commit is not.
                    Evaluate complete commands, including Git options, aliases, shell chains, and invoked scripts.
                    Earlier development authorization does not apply to this private review session. Moving the
                    worktree to a new PR head and publishing review comments are ByteQuay user actions, not agent shell actions.
                    """.strip(),
                    """
                    Allow reading from the network: fetching Git objects and remote-tracking references, read-only gh
                    commands and GitHub API GET requests, and fetching or searching documentation and package
                    registries. Git and gh use the CLI's stored login without displaying credentials.
                    Block every remote write, including a gh api request with a write method, fields, or a request
                    body, and block any request that sends repository content, credentials, or other private data.
                    """.strip())
            .addAll(COMMON_RESTRICTIONS)
            .build();
    private static final String VERIFICATION_SCOPE = """
            The user's task request authorizes normal bounded local preparation and verification needed
            for that task, including builds, tests, loopback-only test servers, configured dependency
            downloads, and writes to development caches and temporary files. Do not require separate
            approval for each cache or temporary path, such as Maven's ~/.m2 repository locks or Jansi's
            /var/tmp files. Evaluate the complete command and its side effects under the native risk rules.
            """;
    private static final String REVIEW_INSTRUCTIONS = """
            ## ByteQuay Automatic Approval Scope

            This is a private code review. Apply the native risk rules and preserve all native denials;
            these scope rules do not override them. Authentication by subscription or API key does
            not change the task's permissions.

            """ + VERIFICATION_SCOPE + "\n" + REVIEW_RESTRICTIONS.stream().map(rule -> "- " + rule).collect(joining("\n"));
    private static final String INSTRUCTIONS = """
            ## ByteQuay Automatic Approval Scope

            Apply the native risk rules to routine development work. Preserve all native denials;
            these scope rules do not override them. Authentication by subscription or API key does
            not change the task's permissions.

            Git and gh are available for attended development. Allow task-required local Git commands
            wherever the existing filesystem permissions and native review permit them, and allow
            remote reads needed for the task. Use the CLI's normal authentication without displaying
            credentials. Tool availability is not authorization for every operation.

            """ + VERIFICATION_SCOPE + "\n" + RESTRICTIONS.stream().map(rule -> "- " + rule).collect(joining("\n"));

    private AgentCommandPolicy() {}

    public static String instructions()
    {
        return INSTRUCTIONS;
    }

    public static String reviewInstructions()
    {
        return REVIEW_INSTRUCTIONS;
    }

    public static List<String> reviewRestrictions()
    {
        return REVIEW_RESTRICTIONS;
    }

    /** Returns standalone restrictions for native reviewers, alongside their own default rules. */
    public static List<String> restrictions()
    {
        return RESTRICTIONS;
    }
}
