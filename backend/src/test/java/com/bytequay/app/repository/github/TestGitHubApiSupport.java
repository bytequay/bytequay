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
package com.bytequay.app.repository.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestGitHubApiSupport
{
    @Test
    void summarizesAnHtmlErrorPageInsteadOfDumpingIt()
    {
        String unicorn = "<!DOCTYPE html>\n<html><head><title>Unicorn!</title></head>"
                + "<body>" + "x".repeat(5000) + "</body></html>";

        String summary = GitHubApiSupport.summarizeErrorBody(unicorn);

        assertThat(summary).startsWith("(HTML error page,");
        assertThat(summary).contains(String.valueOf(unicorn.length()));
        assertThat(summary).doesNotContain("Unicorn");
    }

    @Test
    void keepsSmallJsonErrorsVerbatim()
    {
        String json = "{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com\"}";

        assertThat(GitHubApiSupport.summarizeErrorBody(json)).isEqualTo(json);
    }

    @Test
    void reportsEmptyBodies()
    {
        assertThat(GitHubApiSupport.summarizeErrorBody(null)).isEqualTo("(empty body)");
        assertThat(GitHubApiSupport.summarizeErrorBody("   ")).isEqualTo("(empty body)");
    }

    @Test
    void truncatesAnOversizedNonHtmlBody()
    {
        String big = "y".repeat(4000);

        String summary = GitHubApiSupport.summarizeErrorBody(big);

        assertThat(summary).hasSizeLessThan(big.length());
        assertThat(summary).endsWith("(truncated, 4000 bytes)");
    }
}
