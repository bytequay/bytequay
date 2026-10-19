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
package com.bytequay.app.jira;

import com.bytequay.app.json.JsonContracts;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.JiraConnectionStore;
import com.bytequay.integration.jira.JiraClient;
import com.bytequay.integration.jira.impl.DefaultJiraClient;
import com.bytequay.integration.jira.impl.DefaultJiraConnections;
import com.bytequay.integration.jira.impl.JiraActivityLog;
import com.bytequay.integration.jira.impl.JiraCache;
import com.bytequay.integration.jira.impl.JiraHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JiraIntegrationConfiguration
{
    @Bean
    JiraCache jiraCache(AppSettingsStore settings, ObjectMapper mapper)
    {
        return new JiraCache(settings, mapper);
    }

    @Bean
    DefaultJiraConnections jiraConnections(JiraConnectionStore store, JiraCache cache, ObjectMapper mapper, @Value("${bytequay.jira.auth-url:}") String authUrl)
    {
        return new DefaultJiraConnections(store, cache, new JiraHttpClient(mapper), authUrl);
    }

    @Bean
    JiraActivityLog jiraActivity(JiraCache cache)
    {
        return new JiraActivityLog(cache);
    }

    @Bean(destroyMethod = "close")
    JiraClient jiraClient(DefaultJiraConnections connections, JiraCache cache, JiraActivityLog activity, JiraIssueContextImpl context)
    {
        return new DefaultJiraClient(connections, cache, activity, context, JsonContracts.mapper());
    }
}
