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
package org.apache.coyote;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.ResponseUtil;
import org.apache.tomcat.util.http.parser.AcceptEncoding;
import org.apache.tomcat.util.http.parser.TE;
import org.apache.tomcat.util.http.parser.TokenList;
import org.apache.tomcat.util.res.StringManager;

public class CompressionConfig {

    private static final Log log = LogFactory.getLog(CompressionConfig.class);
    private static final StringManager sm = StringManager.getManager(CompressionConfig.class);

    private int compressionLevel = 0;
    private Pattern noCompressionUserAgents = null;
    private String compressibleMimeType = "text/html,text/xml,text/plain,text/css," +
            "text/javascript,application/javascript,application/json,application/xml";
    private String[] compressibleMimeTypes = null;
    private int compressionMinSize = 2048;
    private boolean noCompressionStrongETag = true;


    /**
     * Set compression level.
     *
     * @param compression One of <code>on</code>, <code>force</code>, <code>off</code> or the minimum compression size
     *                        in bytes which implies <code>on</code>
     */
    public void setCompression(String compression) {
        if (compression.equals("on")) {
            this.compressionLevel = 1;
        } else if (compression.equals("force")) {
            this.compressionLevel = 2;
        } else if (compression.equals("off")) {
            this.compressionLevel = 0;
        } else {
            try {
                // Try to parse compression as an int, which would give the
                // minimum compression size
                setCompressionMinSize(Integer.parseInt(compression));
                this.compressionLevel = 1;
            } catch (Exception e) {
                this.compressionLevel = 0;
            }
        }
    }


    /**
     * Return compression level.
     *
     * @return The current compression level in string form (off/on/force)
     */
    public String getCompression() {
        switch (compressionLevel) {
            case 0:
                return "off";
            case 1:
                return "on";
            case 2:
                return "force";
        }
        return "off";
    }


    public int getCompressionLevel() {
        return compressionLevel;
    }


    /**
     * Obtain the String form of the regular expression that defines the user agents to not use gzip with.
     *
     * @return The regular expression as a String
     */
    public String getNoCompressionUserAgents() {
        if (noCompressionUserAgents == null) {
            return null;
        } else {
            return noCompressionUserAgents.toString();
        }
    }


    public Pattern getNoCompressionUserAgentsPattern() {
        return noCompressionUserAgents;
    }


    /**
     * Set no compression user agent pattern. Regular expression as supported by {@link Pattern}. e.g.:
     * <code>gorilla|desesplorer|tigrus</code>.
     *
     * @param noCompressionUserAgents The regular expression for user agent strings for which compression should not be
     *                                    applied
     */
    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        if (noCompressionUserAgents == null || noCompressionUserAgents.isEmpty()) {
            this.noCompressionUserAgents = null;
        } else {
            this.noCompressionUserAgents = Pattern.compile(noCompressionUserAgents);
        }
    }


    public String getCompressibleMimeType() {
        return compressibleMimeType;
    }


    public void setCompressibleMimeType(String valueS) {
        compressibleMimeType = valueS;
        compressibleMimeTypes = null;
    }


    public String[] getCompressibleMimeTypes() {
        String[] result = compressibleMimeTypes;
        if (result != null) {
            return result;
        }
        List<String> values = new ArrayList<>();
        StringTokenizer tokens = new StringTokenizer(compressibleMimeType, ",");
        while (tokens.hasMoreTokens()) {
            String token = tokens.nextToken().trim();
            if (!token.isEmpty()) {
                values.add(token);
            }
        }
        result = values.toArray(new String[0]);
        compressibleMimeTypes = result;
        return result;
    }


    public int getCompressionMinSize() {
        return compressionMinSize;
    }


    /**
     * Set Minimum size to trigger compression.
     *
     * @param compressionMinSize The minimum content length required for compression in bytes
     */
    public void setCompressionMinSize(int compressionMinSize) {
        this.compressionMinSize = compressionMinSize;
    }


    /**
     * Determine if compression is disabled if the resource has a strong ETag.
     *
     * @return {@code true} if compression is disabled, otherwise {@code false}
     *
     * @deprecated Will be removed in Tomcat 10 where it will be hard-coded to {@code true}
     */
    @Deprecated
    public boolean getNoCompressionStrongETag() {
        return noCompressionStrongETag;
    }


    /**
     * Set whether compression is disabled for resources with a strong ETag.
     *
     * @param noCompressionStrongETag {@code true} if compression is disabled, otherwise {@code false}
     *
     * @deprecated Will be removed in Tomcat 10 where it will be hard-coded to {@code true}
     */
    @Deprecated
    public void setNoCompressionStrongETag(boolean noCompressionStrongETag) {
        this.noCompressionStrongETag = noCompressionStrongETag;
    }


    /**
     * Determines if compression should be enabled for the given response and if it is, sets any necessary headers to
     * mark it as such.
     *
     * @param request  The request that triggered the response
     * @param response The response to consider compressing
     *
     * @return {@code true} if compression was enabled for the given response, otherwise {@code false}
     */
    public boolean useCompression(Request request, Response response) {
        // Check if compression is enabled
        if (compressionLevel == 0) {
            return false;
        }

        boolean useTransferEncoding = false;
        boolean useContentEncoding = true;

        MimeHeaders responseHeaders = response.getMimeHeaders();

        // Check if content is not already compressed
        MessageBytes contentEncodingMB = responseHeaders.getValue("Content-Encoding");
        if (contentEncodingMB != null) {
            // Content-Encoding values are ordered but order is not important
            // for this check so use a Set rather than a List
            Set<String> tokens = new HashSet<>();
            try {
                TokenList.parseTokenList(responseHeaders.values("Content-Encoding"), tokens);
            } catch (IOException e) {
                // Because we are using StringReader, any exception here is a
                // Tomcat bug.
                log.warn(sm.getString("compressionConfig.ContentEncodingParseFail"), e);
                return false;
            }
            if (tokens.contains("identity")) {
                // If identity, do not do content modifications
                useContentEncoding = false;
            } else if (tokens.contains("br") || tokens.contains("compress") || tokens.contains("dcb")
                    || tokens.contains("dcz") || tokens.contains("deflate") || tokens.contains("gzip")
                    || tokens.contains("pack200-gzip") || tokens.contains("zstd")) {
                // Content should not be compressed twice
                return false;
            }
        }

        // If force mode, the length and MIME type checks are skipped
        if (compressionLevel != 2) {
            // Check if the response is of sufficient length to trigger the compression
            long contentLength = response.getContentLengthLong();
            if (contentLength != -1 && contentLength < compressionMinSize) {
                return false;
            }

            // Check for compatible MIME-TYPE
            String[] compressibleMimeTypes = getCompressibleMimeTypes();
            if (compressibleMimeTypes != null &&
                    !startsWithStringArray(compressibleMimeTypes, response.getContentType())) {
                return false;
            }
        }

        // Check if the resource has a strong ETag
        if (noCompressionStrongETag) {
            String eTag = responseHeaders.getHeader("ETag");
            if (eTag != null && !eTag.trim().startsWith("W/")) {
                // Has an ETag that doesn't start with "W/..." so it must be a
                // strong ETag
                return false;
            }
        }

        Enumeration<String> headerValues = request.getMimeHeaders().values("TE");
        boolean foundGzip = false;
        // TE and accept-encoding seem to have equivalent syntax
        while (!foundGzip && headerValues.hasMoreElements()) {
            List<TE> tes;
            try {
                tes = TE.parse(new StringReader(headerValues.nextElement()));
            } catch (IOException ioe) {
                // If there is a problem reading the header, disable compression
                return false;
            }

            for (TE te : tes) {
                if ("gzip".equalsIgnoreCase(te.getEncoding())) {
                    useTransferEncoding = true;
                    foundGzip = true;
                    break;
                }
            }
        }

        if (useContentEncoding && !useTransferEncoding) {
            // If processing reaches this far, the response might be compressed.
            // Therefore, set the Vary header to keep proxies happy
            ResponseUtil.addVaryFieldName(responseHeaders, "accept-encoding");

            // Check if user-agent supports gzip encoding
            // Only interested in whether gzip encoding is supported. Other
            // encodings and weights can be ignored.
            headerValues = request.getMimeHeaders().values("accept-encoding");
            while (!foundGzip && headerValues.hasMoreElements()) {
                List<AcceptEncoding> acceptEncodings;
                try {
                    acceptEncodings = AcceptEncoding.parse(new StringReader(headerValues.nextElement()));
                } catch (IOException ioe) {
                    // If there is a problem reading the header, disable compression
                    return false;
                }

                for (AcceptEncoding acceptEncoding : acceptEncodings) {
                    if ("gzip".equalsIgnoreCase(acceptEncoding.getEncoding())) {
                        foundGzip = true;
                        break;
                    }
                }
            }
        }

        if (!foundGzip) {
            return false;
        }

        // If force mode, the browser checks are skipped
        if (compressionLevel != 2) {
            // Check for incompatible Browser
            Pattern noCompressionUserAgents = this.noCompressionUserAgents;
            if (noCompressionUserAgents != null) {
                MessageBytes userAgentValueMB = request.getMimeHeaders().getValue("user-agent");
                if (userAgentValueMB != null) {
                    String userAgentValue = userAgentValueMB.toString();
                    if (noCompressionUserAgents.matcher(userAgentValue).matches()) {
                        return false;
                    }
                }
            }
        }

        // All checks have passed. Compression is enabled.

        // Compressed content length is unknown so mark it as such.
        response.setContentLength(-1);
        if (useTransferEncoding) {
            // Configure the transfer encoding for compressed content
            responseHeaders.addValue("Transfer-Encoding").setString("gzip");
        } else {
            // Configure the content encoding for compressed content
            responseHeaders.addValue("Content-Encoding").setString("gzip");
        }

        return true;
    }


    /**
     * Checks if any entry in the string array starts with the specified value
     *
     * @param sArray the StringArray
     * @param value  string
     */
    private static boolean startsWithStringArray(String[] sArray, String value) {
        if (value == null) {
            return false;
        }
        for (String s : sArray) {
            if (value.startsWith(s)) {
                return true;
            }
        }
        return false;
    }
}
