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
package com.bytequay.app.service.credentials;

import static java.util.Objects.requireNonNull;

/** Fixed-width masking for stored secrets; never reveals the secret's length. */
public final class SecretMasker
{
    private static final String DOTS = "••••••••••••••••••••";

    private SecretMasker() {}

    public static String mask(String secret)
    {
        requireNonNull(secret, "secret is null");
        if (secret.length() <= 12) {
            return DOTS;
        }
        String prefix = secret.startsWith("github_pat_")
                ? secret.substring(0, "github_pat_".length() + 3)
                : secret.substring(0, 5);
        return prefix + DOTS + secret.substring(secret.length() - 4);
    }
}
