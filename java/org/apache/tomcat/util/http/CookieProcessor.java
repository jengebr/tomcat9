/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.http;

import java.nio.charset.Charset;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

public interface CookieProcessor {

    /**
     * Parse the provided headers into server cookie objects.
     *
     * @param headers       The HTTP headers to parse
     * @param serverCookies The server cookies object to populate with the results of the parsing
     */
    void parseCookieHeader(MimeHeaders headers, ServerCookies serverCookies);

    /**
     * Generate the {@code Set-Cookie} HTTP header value for the given Cookie.
     *
     * @param cookie The cookie for which the header will be generated
     *
     * @return The header value in a form that can be added directly to the response
     *
     * @deprecated This method has been replaced with {@link #generateHeader(Cookie, HttpServletRequest)} and will be
     *                 removed from Tomcat 10 onwards.
     */
    @Deprecated
    String generateHeader(Cookie cookie);

    /**
     * Generate the {@code Set-Cookie} HTTP header value for the given Cookie. This method receives as parameter the
     * servlet request so that it can make decisions based on request properties. One such use-case is deciding if the
     * SameSite attribute should be added to the cookie based on the User-Agent or other request header because there
     * are browser versions incompatible with the SameSite attribute. This is described by
     * <a href="https://www.chromium.org/updates/same-site/incompatible-clients">the Chromium project</a>.
     * <p>
     * The default implementation calls the deprecated {@link #generateHeader(Cookie)} method. Implementors should not
     * rely on this default method as it is present only for transitional compatibility and will be removed in Tomcat 10
     * at the same time as the {@link #generateHeader(Cookie)} method.
     *
     * @param request The servlet request
     * @param cookie  The cookie for which the header will be generated
     *
     * @return The header value in a form that can be added directly to the response
     */
    default String generateHeader(Cookie cookie, HttpServletRequest request) {
        return generateHeader(cookie);
    }

    /**
     * Obtain the character set that will be used when converting between bytes and characters when parsing and/or
     * generating HTTP headers for cookies.
     *
     * @return The character set used for byte&lt;-&gt;character conversions
     */
    Charset getCharset();
}
