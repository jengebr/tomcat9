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
package org.apache.catalina.ssi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;

import org.apache.catalina.util.Strftime;
import org.apache.catalina.util.URLEncoder;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.security.Escape;

/**
 * Allows the different SSICommand implementations to share data/talk to each other
 *
 * @author Bip Thelin
 * @author Amy Roh
 * @author Paul Speed
 * @author Dan Sandberg
 * @author David Becker
 */
public class SSIMediator {
    private static final StringManager sm = StringManager.getManager(SSIMediator.class);

    protected static final String ENCODING_NONE = "none";
    protected static final String ENCODING_ENTITY = "entity";
    protected static final String ENCODING_URL = "url";

    protected static final String DEFAULT_CONFIG_ERR_MSG = "[an error occurred while processing this directive]";
    protected static final String DEFAULT_CONFIG_TIME_FMT = "%A, %d-%b-%Y %T %Z";
    protected static final String DEFAULT_CONFIG_SIZE_FMT = "abbrev";

    protected String configErrMsg = DEFAULT_CONFIG_ERR_MSG;
    protected String configTimeFmt = DEFAULT_CONFIG_TIME_FMT;
    protected String configSizeFmt = DEFAULT_CONFIG_SIZE_FMT;
    protected final String className = getClass().getName();
    protected final SSIExternalResolver ssiExternalResolver;
    protected final long lastModifiedDate;
    protected Strftime strftime;
    protected final SSIConditionalState conditionalState = new SSIConditionalState();
    protected int lastMatchCount = 0;


    public SSIMediator(SSIExternalResolver ssiExternalResolver, long lastModifiedDate) {
        this.ssiExternalResolver = ssiExternalResolver;
        this.lastModifiedDate = lastModifiedDate;
        setConfigTimeFmt(DEFAULT_CONFIG_TIME_FMT, true);
    }


    public void setConfigErrMsg(String configErrMsg) {
        this.configErrMsg = configErrMsg;
    }


    public void setConfigTimeFmt(String configTimeFmt) {
        setConfigTimeFmt(configTimeFmt, false);
    }


    public void setConfigTimeFmt(String configTimeFmt, boolean fromConstructor) {
        this.configTimeFmt = configTimeFmt;
        this.strftime = new Strftime(configTimeFmt, Locale.US);
        /*
         * Variables like DATE_LOCAL, DATE_GMT, and LAST_MODIFIED need to be updated when the timefmt changes. This is
         * what Apache SSI does.
         */
        setDateVariables(fromConstructor);
    }


    public void setConfigSizeFmt(String configSizeFmt) {
        this.configSizeFmt = configSizeFmt;
    }


    public String getConfigErrMsg() {
        return configErrMsg;
    }


    public String getConfigTimeFmt() {
        return configTimeFmt;
    }


    public String getConfigSizeFmt() {
        return configSizeFmt;
    }


    public SSIConditionalState getConditionalState() {
        return conditionalState;
    }


    public Collection<String> getVariableNames() {
        Set<String> variableNames = new HashSet<>();
        // These built-in variables are supplied by the mediator (if not over-written by the user) and always exist
        variableNames.add("DATE_GMT");
        variableNames.add("DATE_LOCAL");
        variableNames.add("LAST_MODIFIED");
        ssiExternalResolver.addVariableNames(variableNames);
        // Remove any variables that are reserved by this class
        variableNames.removeIf(this::isNameReserved);
        return variableNames;
    }


    public long getFileSize(String path, boolean virtual) throws IOException {
        return ssiExternalResolver.getFileSize(path, virtual);
    }


    public long getFileLastModified(String path, boolean virtual) throws IOException {
        return ssiExternalResolver.getFileLastModified(path, virtual);
    }


    public String getFileText(String path, boolean virtual) throws IOException {
        return ssiExternalResolver.getFileText(path, virtual);
    }


    protected boolean isNameReserved(String name) {
        return name.startsWith(className + ".");
    }


    public String getVariableValue(String variableName) {
        return getVariableValue(variableName, ENCODING_NONE);
    }


    public void setVariableValue(String variableName, String variableValue) {
        if (!isNameReserved(variableName)) {
            ssiExternalResolver.setVariableValue(variableName, variableValue);
        }
    }


    public String getVariableValue(String variableName, String encoding) {
        String lowerCaseVariableName = variableName.toLowerCase(Locale.ENGLISH);
        String variableValue = null;
        if (!isNameReserved(lowerCaseVariableName)) {
            // Try getting it externally first, if it fails, try getting the 'built-in' value
            variableValue = ssiExternalResolver.getVariableValue(variableName);
            if (variableValue == null) {
                variableName = variableName.toUpperCase(Locale.ENGLISH);
                variableValue = ssiExternalResolver.getVariableValue(className + "." + variableName);
            }
            if (variableValue != null) {
                variableValue = encode(variableValue, encoding);
            }
        }
        return variableValue;
    }


    /**
     * Applies variable substitution to the specified String and returns the new resolved string.
     *
     * @param val The value which should be checked
     *
     * @return the value after variable substitution
     */
    public String substituteVariables(String val) {
        // If it has no references or HTML entities then no work need to be done
        if (val.indexOf('$') < 0 && val.indexOf('&') < 0) {
            return val;
        }

        // HTML decoding
        val = val.replace("&lt;", "<");
        val = val.replace("&gt;", ">");
        val = val.replace("&quot;", "\"");
        val = val.replace("&amp;", "&");

        StringBuilder sb = new StringBuilder(val);
        int charStart = sb.indexOf("&#");
        while (charStart > -1) {
            int charEnd = sb.indexOf(";", charStart);
            if (charEnd > -1) {
                char c = (char) Integer.parseInt(sb.substring(charStart + 2, charEnd));
                sb.delete(charStart, charEnd + 1);
                sb.insert(charStart, c);
                charStart = sb.indexOf("&#");
            } else {
                break;
            }
        }

        for (int i = 0; i < sb.length();) {
            // Find the next $
            for (; i < sb.length(); i++) {
                if (sb.charAt(i) == '$') {
                    i++;
                    break;
                }
            }
            if (i == sb.length()) {
                break;
            }
            // Check to see if the $ is escaped
            if (i > 1 && sb.charAt(i - 2) == '\\') {
                sb.deleteCharAt(i - 2);
                i--;
                continue;
            }
            int nameStart = i;
            int start = i - 1;
            int end;
            int nameEnd;
            char endChar = ' ';
            // Check for {} wrapped var
            if (sb.charAt(i) == '{') {
                nameStart++;
                endChar = '}';
            }
            // Find the end of the var reference
            for (; i < sb.length(); i++) {
                if (sb.charAt(i) == endChar) {
                    break;
                }
            }
            end = i;
            nameEnd = end;
            if (endChar == '}') {
                end++;
            }
            // We should now have enough to extract the var name
            String varName = sb.substring(nameStart, nameEnd);
            String value = getVariableValue(varName);
            if (value == null) {
                value = "";
            }
            // Replace the var name with its value
            sb.replace(start, end, value);
            // Start searching for the next $ after the value that was just substituted.
            i = start + value.length();
        }
        return sb.toString();
    }


    protected String formatDate(Date date, TimeZone timeZone) {
        String retVal;
        if (timeZone != null) {
            // we temporarily change strftime. Since SSIMediator is inherently single-threaded, this isn't a problem
            TimeZone oldTimeZone = strftime.getTimeZone();
            strftime.setTimeZone(timeZone);
            retVal = strftime.format(date);
            strftime.setTimeZone(oldTimeZone);
        } else {
            retVal = strftime.format(date);
        }
        return retVal;
    }


    protected String encode(String value, String encoding) {
        String retVal;
        if (encoding.equalsIgnoreCase(ENCODING_URL)) {
            retVal = URLEncoder.DEFAULT.encode(value, StandardCharsets.UTF_8);
        } else if (encoding.equalsIgnoreCase(ENCODING_NONE)) {
            retVal = value;
        } else if (encoding.equalsIgnoreCase(ENCODING_ENTITY)) {
            retVal = Escape.htmlElementContent(value);
        } else {
            // This shouldn't be possible
            throw new IllegalArgumentException(sm.getString("ssiMediator.unknownEncoding", encoding));
        }
        return retVal;
    }


    public void log(String message) {
        ssiExternalResolver.log(message, null);
    }


    public void log(String message, Throwable throwable) {
        ssiExternalResolver.log(message, throwable);
    }


    protected void setDateVariables(boolean fromConstructor) {
        boolean alreadySet = ssiExternalResolver.getVariableValue(className + ".alreadyset") != null;
        // skip this if we are being called from the constructor, and this has already been set
        if (!(fromConstructor && alreadySet)) {
            ssiExternalResolver.setVariableValue(className + ".alreadyset", "true");
            Date date = new Date();
            TimeZone timeZone = TimeZone.getTimeZone("GMT");
            String retVal = formatDate(date, timeZone);
            /*
             * If we are setting on of the date variables, we want to remove them from the user defined list of
             * variables, because this is what Apache does.
             */
            setVariableValue("DATE_GMT", null);
            ssiExternalResolver.setVariableValue(className + ".DATE_GMT", retVal);
            retVal = formatDate(date, null);
            setVariableValue("DATE_LOCAL", null);
            ssiExternalResolver.setVariableValue(className + ".DATE_LOCAL", retVal);
            retVal = formatDate(new Date(lastModifiedDate), null);
            setVariableValue("LAST_MODIFIED", null);
            ssiExternalResolver.setVariableValue(className + ".LAST_MODIFIED", retVal);
        }
    }


    protected void clearMatchGroups() {
        for (int i = 1; i <= lastMatchCount; i++) {
            setVariableValue(Integer.toString(i), "");
        }
        lastMatchCount = 0;
    }


    protected void populateMatchGroups(Matcher matcher) {
        lastMatchCount = matcher.groupCount();
        // $0 is not used
        if (lastMatchCount == 0) {
            return;
        }
        for (int i = 1; i <= lastMatchCount; i++) {
            setVariableValue(Integer.toString(i), matcher.group(i));
        }
    }
}
