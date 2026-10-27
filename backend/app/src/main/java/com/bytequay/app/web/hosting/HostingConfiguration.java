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
package com.bytequay.app.web.hosting;

import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.integration.github.GitHubIntegration;
import com.bytequay.integration.gitlab.GitLabIntegration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HostingConfiguration
{
    @Bean
    HostingIntegration gitHubHostingIntegration(@Qualifier("hostingHttpClient") HttpClient http, ObjectMapper mapper)
    {
        return GitHubIntegration.create(http, mapper);
    }

    @Bean
    HostingIntegration gitLabHostingIntegration(@Qualifier("hostingHttpClient") HttpClient http, ObjectMapper mapper)
    {
        return GitLabIntegration.create(http, mapper);
    }

    @Bean(destroyMethod = "close")
    HttpClient hostingHttpClient()
    {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
