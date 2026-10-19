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
package com.bytequay.app.google;

import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.integration.google.impl.DefaultGoogleWorkspace;
import com.bytequay.integration.google.impl.WorkspaceConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class GoogleIntegrationConfiguration
{
    @Bean
    public WorkspaceConnector workspaceConnector(ObjectMapper mapper,
            @Value("${bytequay.google.extension-directory:${user.home}/.gemini/extensions/google-workspace}") Path directory,
            @Value("${bytequay.google.node:node}") String node,
            @Value("${bytequay.google.gemini:gemini}") String gemini,
            @Value("${bytequay.google.npm:npm}") String npm)
    {
        return new WorkspaceConnector(mapper, directory, node, gemini, npm);
    }

    @Bean(initMethod = "loadAccounts", destroyMethod = "close")
    public DefaultGoogleWorkspace googleWorkspace(WorkspaceConnector connector, AppSettingsStore settings, ObjectMapper mapper)
    {
        return new DefaultGoogleWorkspace(connector, settings, mapper);
    }
}
