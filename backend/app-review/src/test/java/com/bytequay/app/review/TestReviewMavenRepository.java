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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewMavenRepository
{
    @Test
    void eachReviewedHeadHasItsOwnDirectoryInFrontOfTheSharedRepository()
            throws IOException
    {
        Path first = ReviewMavenRepository.local("3f0c1a52-review", "1111111111111111111111111111111111111111");
        assertThat(first).isEqualTo(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().resolve("bytequay-review-m2/3f0c1a52-review/111111111111"));
        assertThat(ReviewMavenRepository.local("3f0c1a52-review", "2222222222222222222222222222222222222222")).isNotEqualTo(first);
        assertThat(ReviewMavenRepository.shared()).isEqualTo(Path.of(System.getProperty("user.home"), ".m2", "repository"));

        assertThatThrownBy(() -> ReviewMavenRepository.local("../escape", "1111")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewMavenRepository.local("review", "1111/../..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewMavenRepository.delete("review/..")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingRemovesEveryHeadOfTheReviewAndNeverFollowsALink(@TempDir Path outside)
            throws IOException
    {
        String reviewId = "test-" + UUID.randomUUID();
        Path kept = Files.writeString(outside.resolve("kept.jar"), "kept");
        Path first = ReviewMavenRepository.local(reviewId, "1111111111111111");
        Files.writeString(Files.createDirectories(first.resolve("org/example/widget")).resolve("widget.jar"), "installed");
        Path second = Files.createDirectories(ReviewMavenRepository.local(reviewId, "2222222222222222"));
        Files.createSymbolicLink(second.resolve("linked"), outside);
        String other = "test-" + UUID.randomUUID();
        Path untouched = Files.createDirectories(ReviewMavenRepository.local(other, "1111111111111111"));

        ReviewMavenRepository.delete(reviewId);

        assertThat(first.getParent()).doesNotExist();
        assertThat(kept).hasContent("kept");
        assertThat(untouched).isDirectory();
        ReviewMavenRepository.delete(other);
        // A review that never built anything has nothing to remove.
        ReviewMavenRepository.delete(other);
        assertThat(untouched.getParent()).doesNotExist();
    }
}
