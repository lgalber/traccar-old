/*
 * Copyright 2026 Luis Albertinazzi
 *
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
package org.traccar.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Set;

/**
 * Marks the web app's entry files (page, service worker, manifest) as "private, no-cache", so browsers check for
 * a new version on every load and shared caches such as Cloudflare do not keep a copy. Otherwise phones can keep
 * running an old app version for hours after a deploy. Versioned files under /assets/ keep the normal caching.
 */
public class NoCacheEntryFilter implements Filter {

    static final String NO_CACHE = "private, no-cache";

    private static final Set<String> ENTRY_PATHS = Set.of(
            "/", "/index.html", "/sw.js", "/push-sw.js", "/registerSW.js", "/manifest.webmanifest");

    static boolean isEntry(String path) {
        return ENTRY_PATHS.contains(path) || (path.startsWith("/workbox-") && path.endsWith(".js"));
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse
                && isEntry(httpRequest.getRequestURI().substring(httpRequest.getContextPath().length()))) {
            httpResponse.setHeader("Cache-Control", NO_CACHE);
            chain.doFilter(request, new HttpServletResponseWrapper(httpResponse) {
                @Override
                public void setHeader(String name, String value) {
                    super.setHeader(name, "Cache-Control".equalsIgnoreCase(name) ? NO_CACHE : value);
                }

                @Override
                public void addHeader(String name, String value) {
                    if ("Cache-Control".equalsIgnoreCase(name)) {
                        super.setHeader(name, NO_CACHE);
                    } else {
                        super.addHeader(name, value);
                    }
                }
            });
        } else {
            chain.doFilter(request, response);
        }
    }

}
