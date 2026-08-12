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
package com.bytequay.app.repository.github;

import com.bytequay.app.repository.GitHubAccountRepository;
import com.bytequay.app.repository.GitHubActionsRepository;
import com.bytequay.app.repository.GitHubIssueRepository;
import com.bytequay.app.repository.GitHubMergeRepository;
import com.bytequay.app.repository.GitHubPullRequestReadRepository;
import com.bytequay.app.repository.GitHubPullRequestWriteRepository;
import com.bytequay.app.repository.PullRequestRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Temporary aggregate bean for callers that have not migrated to a capability interface yet. */
@Configuration
class GitHubRepositoryCompatibilityConfig
{
    @Bean
    @Primary
    PullRequestRepository pullRequestRepository(
            GitHubPullRequestReadRepository reads,
            GitHubPullRequestWriteRepository writes,
            GitHubMergeRepository merges,
            GitHubIssueRepository issues,
            GitHubActionsRepository actions,
            GitHubAccountRepository accounts)
    {
        Map<Class<?>, Object> targets = Map.of(
                GitHubPullRequestReadRepository.class, reads,
                GitHubPullRequestWriteRepository.class, writes,
                GitHubMergeRepository.class, merges,
                GitHubIssueRepository.class, issues,
                GitHubActionsRepository.class, actions,
                GitHubAccountRepository.class, accounts);
        return (PullRequestRepository) Proxy.newProxyInstance(
                PullRequestRepository.class.getClassLoader(),
                new Class<?>[] {PullRequestRepository.class},
                (proxy, method, arguments) -> invoke(proxy, method, arguments, targets));
    }

    private static Object invoke(
            Object proxy, Method method, Object[] arguments, Map<Class<?>, Object> targets)
            throws Throwable
    {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "PullRequestRepository compatibility aggregate";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        Object target = targets.get(method.getDeclaringClass());
        if (target == null) {
            throw new UnsupportedOperationException(
                    "No GitHub capability owns " + method.toGenericString());
        }
        try {
            return method.invoke(target, arguments);
        }
        catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
