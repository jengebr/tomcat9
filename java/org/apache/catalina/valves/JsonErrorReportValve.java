/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.valves;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.coyote.ActionCode;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.json.JSONFilter;
import org.apache.tomcat.util.res.StringManager;

/**
 * Implementation of a Valve that outputs error JSON.
 * This Valve should be attached at the Host level, although it will work if attached to a Context.
 */
public class JsonErrorReportValve extends ErrorReportValve {

    public JsonErrorReportValve() {
        super();
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {

        int statusCode = response.getStatus();

        // Do nothing on a 1xx, 2xx and 3xx status
        // Do nothing if anything has been written already
        // Do nothing if the response hasn't been explicitly marked as in error
        // and that error has not been reported.
        if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }

        // If an error has occurred that prevents further I/O, don't waste time
        // producing an error report that will never be read
        AtomicBoolean result = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, result);
        if (!result.get()) {
            return;
        }

        StringManager smClient = StringManager.getManager(Constants.Package, request.getLocales());
        response.setLocale(smClient.getLocale());
        String type;
        if (throwable != null) {
            type = smClient.getString("errorReportValve.exceptionReport");
        } else {
            type = smClient.getString("errorReportValve.statusReport");
        }
        String message = response.getMessage();
        if (message == null && throwable != null) {
            message = throwable.getMessage();
        }
        if (message == null) {
            message = "";
        }
        String description = smClient.getString("http." + statusCode + ".desc");
        if (description == null) {
            if (message.isEmpty()) {
                return;
            } else {
                description = smClient.getString("errorReportValve.noDescription");
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"type\": \"").append(JSONFilter.escape(type)).append("\",\n");
        sb.append("  \"status\": ").append(statusCode).append(",\n");
        sb.append("  \"message\": \"").append(JSONFilter.escape(message)).append("\",\n");
        sb.append("  \"description\": \"").append(JSONFilter.escape(description));

        if (throwable != null) {
            sb.append("\",\n");

            // Stack trace
            sb.append("  \"throwable\": [");
            boolean first = true;
            do {
                if (!first) {
                    sb.append(',');
                } else {
                    first = false;
                }
                sb.append('\"').append(JSONFilter.escape(throwable.toString())).append('\"');

                StackTraceElement[] elements = throwable.getStackTrace();
                int pos = elements.length;
                for (int i = elements.length - 1; i >= 0; i--) {
                    if ((elements[i].getClassName().startsWith("org.apache.catalina.core.ApplicationFilterChain")) &&
                            (elements[i].getMethodName().equals("internalDoFilter"))) {
                        pos = i;
                        break;
                    }
                }
                for (int i = 0; i < pos; i++) {
                    if (!(elements[i].getClassName().startsWith("org.apache.catalina.core."))) {
                        sb.append(',').append('\"').append(' ').append(JSONFilter.escape(elements[i].toString())).append('\"');
                    }
                }

                throwable = throwable.getCause();
            } while (throwable != null);
            sb.append("]\n}");

        } else {
            sb.append("\"\n}");
        }

        try {
            try {
                response.setContentType("application/json");
                response.setCharacterEncoding("utf-8");
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                if (container.getLogger().isDebugEnabled()) {
                    container.getLogger().debug(sm.getString("errorReportValve.contentTypeFail"), t);
                }
            }
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(sb.toString());
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException e) {
            // Ignore
        }
    }

}
