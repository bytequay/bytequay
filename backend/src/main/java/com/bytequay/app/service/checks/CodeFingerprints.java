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
package com.bytequay.app.service.checks;

import com.bytequay.app.service.local.GitRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static java.util.Objects.requireNonNull;

/**
 * The HEAD/effective-diff fingerprint of a worktree: what exactly a
 * validation pass or review verdict examined. Committed state is the
 * HEAD sha; uncommitted state folds in the porcelain status plus each
 * dirty file's diff, so any code change — committed or not — changes the
 * fingerprint.
 */
@Component
public class CodeFingerprints
{
    private static final int MAX_DIFF_BYTES_PER_FILE = 512 * 1024;

    private final GitRunner git;

    public CodeFingerprints(GitRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    public String fingerprint(Path worktree)
    {
        requireNonNull(worktree, "worktree is null");
        try {
            MessageDigest digest = sha256();
            digest.update(git.headSha(worktree).getBytes(StandardCharsets.UTF_8));
            String status = git.statusPorcelainZ(worktree);
            digest.update(status.getBytes(StandardCharsets.UTF_8));
            for (GitRunner.WorkingTreeFile file : git.workingTreeFiles(worktree)) {
                digest.update(file.path().getBytes(StandardCharsets.UTF_8));
                digest.update(git.workingTreeFileDiff(worktree, file.path(), MAX_DIFF_BYTES_PER_FILE)
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (IOException e) {
            throw new UncheckedIOException("fingerprinting " + worktree + " failed", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fingerprinting " + worktree + " interrupted", e);
        }
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
