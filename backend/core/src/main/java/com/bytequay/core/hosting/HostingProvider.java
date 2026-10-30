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

public enum HostingProvider
{
    GITHUB(true, HostingCapabilities.ALL),
    GITLAB(false, HostingCapabilities.NONE);

    private final boolean pathsIgnoreCase;
    private final HostingCapabilities capabilities;

    HostingProvider(boolean pathsIgnoreCase, HostingCapabilities capabilities)
    {
        this.pathsIgnoreCase = pathsIgnoreCase;
        this.capabilities = capabilities;
    }

    public HostingCapabilities capabilities()
    {
        return capabilities;
    }

    /** Whether two repository paths that differ only in case name the same repository on this provider. */
    public boolean pathsIgnoreCase()
    {
        return pathsIgnoreCase;
    }
}
