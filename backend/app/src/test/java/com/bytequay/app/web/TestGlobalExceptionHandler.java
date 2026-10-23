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
package com.bytequay.app.web;

import com.bytequay.app.review.LocalRunRefusedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestGlobalExceptionHandler
{
    @Test
    void preservesNotFoundStatusForMissingResources()
            throws Exception
    {
        standaloneSetup(new MissingResourceController()).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/ci-autofix/runs/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/ci-autofix/runs/missing"));
    }

    @Test
    void reportsLocalRunRefusalReason()
            throws Exception
    {
        standaloneSetup(new MissingResourceController()).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/agent/ide/refused"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Your login shell environment could not be read"));
    }

    @RestController
    static class MissingResourceController
    {
        @GetMapping("/ci-autofix/runs/missing")
        void missing()
                throws NoResourceFoundException
        {
            throw new NoResourceFoundException(HttpMethod.GET, "ci-autofix/runs/missing");
        }

        @GetMapping("/agent/ide/refused")
        void refused()
        {
            throw new LocalRunRefusedException("Your login shell environment could not be read");
        }
    }
}
