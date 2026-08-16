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
package com.bytequay.agent.core;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;

public record RepositoryIdentity(String owner, String name)
{
    public RepositoryIdentity
    {
        checkPart(owner, "owner");
        checkPart(name, "name");
    }

    public static RepositoryIdentity valueOf(String value)
    {
        checkNotBlank(value, "value");
        String[] parts = value.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("repository must use owner/name format");
        }
        return new RepositoryIdentity(parts[0], parts[1]);
    }

    @Override
    public String toString()
    {
        return owner + "/" + name;
    }

    private static void checkPart(String value, String name)
    {
        checkNotBlank(value, name);
        if (value.contains("/")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
