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
package com.bytequay.core.hosting;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;

/** Account-bound credential; the generation changes whenever authorization is replaced or removed. */
public final class HostingCredential
{
    private final long accountId;
    private final long generation;
    private final String secret;

    public HostingCredential(long accountId, long generation, String secret)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        checkArgument(generation > 0, "generation must be positive");
        this.accountId = accountId;
        this.generation = generation;
        this.secret = checkText(secret, "secret");
    }

    public long accountId()
    {
        return accountId;
    }

    public long generation()
    {
        return generation;
    }

    public String secret()
    {
        return secret;
    }

    @Override
    public String toString()
    {
        return "HostingCredential[accountId=" + accountId + ", generation=" + generation + ", secret=<redacted>]";
    }
}
