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
package com.bytequay.app.cherrypick;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Creates and removes the branch and worktree a cherry-pick range runs in.
 *
 * <p>ByteQuay owns both, which is what makes the detached-HEAD problem
 * impossible: a worktree created with {@code -b} is always on its branch, and
 * the local target branch is never checked out, so it stays synchronizable.
 */
@Component
class CherryPickWorkspace
{
    private static final Logger log = LoggerFactory.getLogger(CherryPickWorkspace.class);
    private static final String SUFFIX_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int NAME_ATTEMPTS = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitCommandRunner git;

    CherryPickWorkspace(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * Creates the work branch and its worktree at the frozen target.
     *
     * <p>Branching from the frozen object ID rather than the local target
     * branch keeps the worktree consistent with the plan even when the branch
     * moves afterwards.
     *
     * @param repository clone that owns the objects
     * @param worktreeParent directory that will hold the generated worktree
     * @param from source range start label, for the branch name
     * @param to source range end label, for the branch name
     * @param frozenTargetSha object ID the branch starts at
     * @return the created branch and worktree
     */
    Created create(Path repository, Path worktreeParent, String from, String to, String frozenTargetSha)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(worktreeParent, "worktreeParent is null");
        requireNonNull(frozenTargetSha, "frozenTargetSha is null");
        try {
            Files.createDirectories(worktreeParent);
        }
        catch (IOException failure) {
            throw new CherryPickConflictException("Cannot prepare the managed worktree directory", failure);
        }
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            String branch = branchName(from, to);
            Path worktree = worktreeParent.resolve(branch);
            Result created = git.run(repository, ImmutableList.of(
                    "worktree", "add", "--quiet", "-b", branch, worktree.toString(), frozenTargetSha));
            if (created.successful()) {
                return new Created(branch, worktree.toAbsolutePath().normalize());
            }
            if (!created.output().contains("already exists")) {
                throw new CherryPickConflictException(
                        "Cannot create the cherry-pick worktree: " + created.output().strip());
            }
        }
        throw new CherryPickConflictException("Cannot find an unused cherry-pick branch name");
    }

    /**
     * Removes a worktree and branch ByteQuay created.
     *
     * <p>Safe to call on a worktree with an open operation: it is ByteQuay's
     * own, and nothing outside it is touched.
     *
     * @param repository clone that owns the worktree
     * @param created branch and worktree to remove
     */
    void remove(Path repository, Created created)
    {
        git.run(created.worktree(), ImmutableList.of("cherry-pick", "--abort"));
        Result removed = git.run(repository, ImmutableList.of(
                "worktree", "remove", "--force", created.worktree().toString()));
        if (!removed.successful()) {
            log.warn("Cannot remove cherry-pick worktree {}: {}", created.worktree(), removed.output().strip());
        }
        git.run(repository, ImmutableList.of("worktree", "prune"));
        git.run(repository, ImmutableList.of("branch", "-D", created.branch()));
    }

    private static String branchName(String from, String to)
    {
        return "bump-" + slug(from) + "-" + slug(to) + "-" + suffix();
    }

    private static String slug(String value)
    {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        cleaned = cleaned.replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (cleaned.isEmpty()) {
            cleaned = "range";
        }
        return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40);
    }

    private static String suffix()
    {
        return String.valueOf(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())))
                + SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length()));
    }

    record Created(String branch, Path worktree)
    {
        Created
        {
            requireNonNull(branch, "branch is null");
            requireNonNull(worktree, "worktree is null");
        }
    }
}
