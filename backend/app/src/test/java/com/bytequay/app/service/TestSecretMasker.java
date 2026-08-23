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
package com.bytequay.app.service;

import com.bytequay.app.service.credentials.SecretMasker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestSecretMasker
{
    @Test
    void gitHubPatKeepsItsPrefixAndSuffix()
    {
        String masked = SecretMasker.mask("github_pat_11ABCDEFGHIJKLMNOPQRSTUVWX3xQz");
        assertThat(masked).startsWith("github_pat_11A").endsWith("3xQz").contains("••••");
        assertThat(masked).doesNotContain("BCDEFGHIJ");
    }

    @Test
    void otherSecretsKeepFiveCharsAndSuffix()
    {
        assertThat(SecretMasker.mask("sk-4fabcdefghijklmnope2a1"))
                .startsWith("sk-4f")
                .endsWith("e2a1")
                .doesNotContain("abcdefgh");
    }

    @Test
    void shortSecretsAreFullyMasked()
    {
        assertThat(SecretMasker.mask("tiny")).doesNotContain("tiny");
    }

    @Test
    void maskedLengthDoesNotRevealSecretLength()
    {
        int shortLength = SecretMasker.mask("ghp_0123456789abc").length();
        int longLength = SecretMasker.mask("ghp_0123456789abc0123456789abc0123456789abc").length();
        assertThat(shortLength).isEqualTo(longLength);
    }
}
