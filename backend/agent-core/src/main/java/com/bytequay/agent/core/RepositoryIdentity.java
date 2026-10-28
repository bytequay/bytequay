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

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;

public record RepositoryIdentity(String owner, String name)
{
    public RepositoryIdentity
    {
        checkText(owner, "owner");
        for (String part : owner.split("/", -1)) {
            checkPart(part, "namespace");
        }
        checkPart(name, "name");
    }

    public static RepositoryIdentity valueOf(String value)
    {
        checkText(value, "value");
        int slash = value.lastIndexOf('/');
        checkArgument(slash > 0 && slash < value.length() - 1, "repository must use namespace/name format");
        return new RepositoryIdentity(value.substring(0, slash), value.substring(slash + 1));
    }

    @Override
    public String toString()
    {
        return owner + "/" + name;
    }

    private static void checkPart(String value, String name)
    {
        checkText(value, name);
        checkArgument(!value.contains("/"), name + " is invalid");
    }
}
