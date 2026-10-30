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

import java.net.URI;

import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static java.util.Objects.requireNonNull;

/** Validated connection coordinates, available before an account or persistent identity exists. */
public record HostingServer(HostingProvider provider, URI webBaseUrl, URI apiBaseUrl)
{
    public static final HostingServer GITHUB_COM = new HostingServer(GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));

    public HostingServer
    {
        requireNonNull(provider, "provider is null");
        webBaseUrl = HostingUrls.canonicalBaseUrl(webBaseUrl);
        apiBaseUrl = HostingUrls.canonicalBaseUrl(apiBaseUrl);
    }

    /**
     * How stored keys and labels name the server: its web address, without the scheme when that is https, such as {@code github.com},
     * the way a hosted issue source reads a host.
     */
    public String host()
    {
        String address = webBaseUrl.toASCIIString();
        return webBaseUrl.getScheme().equals("https") ? address.substring("https://".length()) : address;
    }
}
