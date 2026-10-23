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
package com.bytequay.app.review;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The private Maven repository of one reviewed head. A review's commands write there and read third-party artifacts
 * from the user's shared repository behind it, so a pull request's modules never reach a cache other builds read, and
 * one head's modules are never read for another head.
 */
final class ReviewMavenRepository
{
    // Both values become directory names.
    private static final Pattern NAME = Pattern.compile("[0-9A-Za-z_-]{1,64}");
    private static final int HEAD_LENGTH = 12;

    private ReviewMavenRepository() {}

    static Path local(String reviewId, String headSha)
    {
        checkArgument(NAME.matcher(headSha).matches(), "headSha is not a plain revision");
        return root(reviewId).resolve(headSha.substring(0, Math.min(HEAD_LENGTH, headSha.length())));
    }

    /** Maven's default shared repository. A localRepository chosen in settings.xml is not read, so its artifacts are downloaded again. */
    static Path shared()
    {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /** Removes every head's repository of a review; a link inside is removed, never followed. */
    static void delete(String reviewId)
            throws IOException
    {
        Path root = root(reviewId);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            // macOS has no secure directory stream, so the insecure walk is the only option here.
            MoreFiles.deleteRecursively(root, RecursiveDeleteOption.ALLOW_INSECURE);
        }
    }

    // ponytail: java.io.tmpdir is private to the user on macOS, and both providers' sandboxes may write it without a new
    // grant. Use a per-user directory instead if ByteQuay ever runs where the temporary directory is a shared /tmp.
    private static Path root(String reviewId)
    {
        checkArgument(NAME.matcher(reviewId).matches(), "reviewId is not a plain identifier");
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
        try {
            // The sandboxes grant the resolved directory, and on macOS /var is a link.
            temporary = temporary.toRealPath();
        }
        catch (IOException unresolved) {
            temporary = temporary.toAbsolutePath();
        }
        return temporary.resolve("bytequay-review-m2").resolve(reviewId);
    }
}
