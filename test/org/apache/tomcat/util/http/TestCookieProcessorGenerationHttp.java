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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;

public class TestCookieProcessorGenerationHttp extends TomcatBaseTest {

    @Test
    public void testUtf8CookieValue() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        // No file system docBase required
        Context ctx = getProgrammaticRootContext();
        ctx.setCookieProcessor(new Rfc6265CookieProcessor());
        Map<String,String> cookies = new HashMap<>();
        cookies.put("Test", "\u0120");
        Tomcat.addServlet(ctx, "test", new CookieServlet(cookies));
        ctx.addServletMappingDecoded("/test", "test");
        tomcat.start();

        Map<String,List<String>> headers = new HashMap<>();
        ByteChunk res = new ByteChunk();
        getUrl("http://localhost:" + getPort() + "/test", res, headers);
        List<String> cookieHeaders = headers.get("Set-Cookie");
        Assert.assertEquals("There should only be one Set-Cookie header in this test",
                1, cookieHeaders.size());
        // Client is assuming header is ISO-8859-1 encoding which it isn't. Turn
        // the header value back into the received bytes (this isn't guaranteed
        // to work with all values but it will for this test value)
        byte[] headerBytes = cookieHeaders.get(0).getBytes(StandardCharsets.ISO_8859_1);
        // Now convert those bytes to a String using UTF-8
        String utf8Header = new String(headerBytes, StandardCharsets.UTF_8);
        Assert.assertEquals("Test=\u0120", utf8Header);
    }


    private static class CookieServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final Map<String,String> cookieNamesValues;

        CookieServlet(Map<String,String> cookieNamesValues) {
            this.cookieNamesValues = cookieNamesValues;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            for (Map.Entry<String,String> entry : cookieNamesValues.entrySet()) {
                Cookie cookie = new Cookie(entry.getKey(), entry.getValue());
                resp.addCookie(cookie);
            }
            resp.setContentType("text/plain");
            resp.getWriter().print("OK");
        }
    }


    @Test
    public void testCaseSensitiveCookie() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        // No file system docBase required
        Context ctx = getProgrammaticRootContext();
        ctx.setCookieProcessor(new Rfc6265CookieProcessor());
        Map<String,String> cookies = new HashMap<>();
        cookies.put("aaa", "zzz");
        cookies.put("aAa", "yyy");
        Tomcat.addServlet(ctx, "test", new CookieServlet(cookies));
        ctx.addServletMappingDecoded("/test", "test");
        tomcat.start();

        Map<String,List<String>> headers = new HashMap<>();
        ByteChunk res = new ByteChunk();
        getUrl("http://localhost:" + getPort() + "/test", res, headers);
        List<String> cookieHeaders = headers.get("Set-Cookie");
        Assert.assertEquals("There should be two Set-Cookie headers in this test",
                2, cookieHeaders.size());
        // Remove the cookies the client sees from the map that was sent. Should leave the map empty.
        for (String cookieHeader : cookieHeaders) {
            String[] nv = cookieHeader.split("=");
            Assert.assertEquals(2, nv.length);
            Assert.assertTrue(nv[1].equals(cookies.remove(nv[0])));
        }
        Assert.assertEquals(0,  cookies.size());
    }
}
