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
package org.apache.tomcat.websocket.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import javax.naming.NamingException;
import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Configurator;

import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.websocket.WsSession;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import org.apache.tomcat.websocket.pojo.PojoMethodMapping;

/**
 * Provides a per class loader (i.e. per web application) instance of a ServerContainer. Web application wide defaults
 * may be configured by setting the following servlet context initialisation parameters to the desired values.
 * <ul>
 * <li>{@link Constants#BINARY_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM}</li>
 * <li>{@link Constants#TEXT_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM}</li>
 * </ul>
 */
public class WsServerContainer extends WsWebSocketContainer implements ServerContainer {

    private static final StringManager sm = StringManager.getManager(WsServerContainer.class);

    private static final CloseReason AUTHENTICATED_HTTP_SESSION_CLOSED = new CloseReason(CloseCodes.VIOLATED_POLICY,
            "This connection was established under an authenticated " + "HTTP session that has ended.");

    private final WsWriteTimeout wsWriteTimeout = new WsWriteTimeout();

    private final ServletContext servletContext;
    private final Map<String, ExactPathMatch> configExactMatchMap = new ConcurrentHashMap<>();
    private final Map<Integer, ConcurrentSkipListMap<String, TemplatePathMatch>> configTemplateMatchMap = new ConcurrentHashMap<>();
    @SuppressWarnings("deprecation")
    private volatile boolean enforceNoAddAfterHandshake = org.apache.tomcat.websocket.Constants.STRICT_SPEC_COMPLIANCE;
    private volatile boolean addAllowed = true;
    private final Map<String, Set<WsSession>> authenticatedSessions = new ConcurrentHashMap<>();
    private volatile boolean endpointsRegistered = false;
    private volatile boolean deploymentFailed = false;

    @SuppressWarnings("deprecation")
    WsServerContainer(ServletContext servletContext) {

        this.servletContext = servletContext;
        setInstanceManager((InstanceManager) servletContext.getAttribute(InstanceManager.class.getName()));

        // Configure servlet context wide defaults
        String value = servletContext.getInitParameter(Constants.BINARY_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM);
        if (value != null) {
            setDefaultMaxBinaryMessageBufferSize(Integer.parseInt(value));
        }

        value = servletContext.getInitParameter(Constants.TEXT_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM);
        if (value != null) {
            setDefaultMaxTextMessageBufferSize(Integer.parseInt(value));
        }

        value = servletContext.getInitParameter(Constants.ENFORCE_NO_ADD_AFTER_HANDSHAKE_CONTEXT_INIT_PARAM);
        if (value != null) {
            setEnforceNoAddAfterHandshake(Boolean.parseBoolean(value));
        }

        FilterRegistration.Dynamic fr = servletContext.addFilter("Tomcat WebSocket (JSR356) Filter", new WsFilter());
        if (fr != null) {
            fr.setAsyncSupported(true);

            EnumSet<DispatcherType> types = EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD);

            fr.addMappingForUrlPatterns(types, true, "/*");
        }
    }


    /**
     * Published the provided endpoint implementation at the specified path with the specified configuration.
     * {@link #WsServerContainer(ServletContext)} must be called before calling this method.
     *
     * @param sec The configuration to use when creating endpoint instances
     *
     * @throws DeploymentException if the endpoint cannot be published as requested
     */
    @Override
    public void addEndpoint(ServerEndpointConfig sec) throws DeploymentException {
        addEndpoint(sec, false);
    }


    void addEndpoint(ServerEndpointConfig sec, boolean fromAnnotatedPojo) throws DeploymentException {

        if (enforceNoAddAfterHandshake && !addAllowed) {
            throw new DeploymentException(sm.getString("serverContainer.addNotAllowed"));
        }

        if (servletContext == null) {
            throw new DeploymentException(sm.getString("serverContainer.servletContextMissing"));
        }

        if (deploymentFailed) {
            throw new DeploymentException(sm.getString("serverContainer.failedDeployment",
                    servletContext.getContextPath(), servletContext.getVirtualServerName()));
        }

        try {
            String path = sec.getPath();

            // Add method mapping to user properties
            PojoMethodMapping methodMapping = new PojoMethodMapping(sec.getEndpointClass(), sec.getDecoders(), path,
                    getInstanceManager(Thread.currentThread().getContextClassLoader()));
            if (methodMapping.getOnClose() != null || methodMapping.getOnOpen() != null ||
                    methodMapping.getOnError() != null || methodMapping.hasMessageHandlers()) {
                sec.getUserProperties().put(org.apache.tomcat.websocket.pojo.Constants.POJO_METHOD_MAPPING_KEY,
                        methodMapping);
            }

            UriTemplate uriTemplate = new UriTemplate(path);
            if (uriTemplate.hasParameters()) {
                Integer key = Integer.valueOf(uriTemplate.getSegmentCount());
                ConcurrentSkipListMap<String, TemplatePathMatch> templateMatches = configTemplateMatchMap.get(key);
                if (templateMatches == null) {
                    // Ensure that if concurrent threads execute this block they
                    // all end up using the same ConcurrentSkipListMap instance
                    templateMatches = new ConcurrentSkipListMap<>();
                    configTemplateMatchMap.putIfAbsent(key, templateMatches);
                    templateMatches = configTemplateMatchMap.get(key);
                }
                TemplatePathMatch newMatch = new TemplatePathMatch(sec, uriTemplate, fromAnnotatedPojo);
                TemplatePathMatch oldMatch = templateMatches.putIfAbsent(uriTemplate.getNormalizedPath(), newMatch);
                if (oldMatch != null) {
                    // Note: This depends on Endpoint instances being added
                    // before POJOs in WsSci#onStartup()
                    if (oldMatch.isFromAnnotatedPojo() && !newMatch.isFromAnnotatedPojo() &&
                            oldMatch.getConfig().getEndpointClass() == newMatch.getConfig().getEndpointClass()) {
                        // The WebSocket spec says to ignore the new match in this case
                        templateMatches.put(path, oldMatch);
                    } else {
                        // Duplicate uriTemplate;
                        throw new DeploymentException(sm.getString("serverContainer.duplicatePaths", path,
                                sec.getEndpointClass(), sec.getEndpointClass()));
                    }
                }
            } else {
                // Exact match
                ExactPathMatch newMatch = new ExactPathMatch(sec, fromAnnotatedPojo);
                ExactPathMatch oldMatch = configExactMatchMap.put(path, newMatch);
                if (oldMatch != null) {
                    // Note: This depends on Endpoint instances being added
                    // before POJOs in WsSci#onStartup()
                    if (oldMatch.isFromAnnotatedPojo() && !newMatch.isFromAnnotatedPojo() &&
                            oldMatch.getConfig().getEndpointClass() == newMatch.getConfig().getEndpointClass()) {
                        // The WebSocket spec says to ignore the new match in this case
                        configExactMatchMap.put(path, oldMatch);
                    } else {
                        // Duplicate path mappings
                        throw new DeploymentException(sm.getString("serverContainer.duplicatePaths", path,
                                oldMatch.getConfig().getEndpointClass(), sec.getEndpointClass()));
                    }
                }
            }

            endpointsRegistered = true;
        } catch (DeploymentException de) {
            failDeployment();
            throw de;
        }
    }


    /**
     * Provides the equivalent of {@link #addEndpoint(ServerEndpointConfig)} for publishing plain old java objects
     * (POJOs) that have been annotated as WebSocket endpoints.
     *
     * @param pojo The annotated POJO
     */
    @Override
    public void addEndpoint(Class<?> pojo) throws DeploymentException {
        addEndpoint(pojo, false);
    }


    void addEndpoint(Class<?> pojo, boolean fromAnnotatedPojo) throws DeploymentException {

        if (deploymentFailed) {
            throw new DeploymentException(sm.getString("serverContainer.failedDeployment",
                    servletContext.getContextPath(), servletContext.getVirtualServerName()));
        }

        ServerEndpointConfig sec;

        try {
            ServerEndpoint annotation = pojo.getAnnotation(ServerEndpoint.class);
            if (annotation == null) {
                throw new DeploymentException(sm.getString("serverContainer.missingAnnotation", pojo.getName()));
            }
            String path = annotation.value();

            // Validate encoders
            validateEncoders(annotation.encoders(), getInstanceManager(Thread.currentThread().getContextClassLoader()));

            // ServerEndpointConfig
            Class<? extends Configurator> configuratorClazz = annotation.configurator();
            Configurator configurator = null;
            if (!configuratorClazz.equals(Configurator.class)) {
                try {
                    configurator = annotation.configurator().getConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new DeploymentException(sm.getString("serverContainer.configuratorFail",
                            annotation.configurator().getName(), pojo.getName()), e);
                }
            }
            sec = ServerEndpointConfig.Builder.create(pojo, path).decoders(Arrays.asList(annotation.decoders()))
                    .encoders(Arrays.asList(annotation.encoders()))
                    .subprotocols(Arrays.asList(annotation.subprotocols())).configurator(configurator).build();
        } catch (DeploymentException de) {
            failDeployment();
            throw de;
        }

        addEndpoint(sec, fromAnnotatedPojo);
    }


    void failDeployment() {
        deploymentFailed = true;

        // Clear all existing deployments
        endpointsRegistered = false;
        configExactMatchMap.clear();
        configTemplateMatchMap.clear();
    }


    boolean areEndpointsRegistered() {
        return endpointsRegistered;
    }


    /**
     * Until the WebSocket specification provides such a mechanism, this Tomcat proprietary method is provided to enable
     * applications to programmatically determine whether or not to upgrade an individual request to WebSocket.
     * <p>
     * Note: This method is not used by Tomcat but is used directly by third-party code and must not be removed.
     *
     * @param request    The request object to be upgraded
     * @param response   The response object to be populated with the result of the upgrade
     * @param sec        The server endpoint to use to process the upgrade request
     * @param pathParams The path parameters associated with the upgrade request
     *
     * @throws ServletException If a configuration error prevents the upgrade from taking place
     * @throws IOException      If an I/O error occurs during the upgrade process
     *
     * @deprecated This method will be removed in Apache Tomcat 10.1 onwards. It has been replaced by
     *                 {@link #upgradeHttpToWebSocket(Object, Object, ServerEndpointConfig, Map)}
     */
    @Deprecated
    public void doUpgrade(HttpServletRequest request, HttpServletResponse response, ServerEndpointConfig sec,
            Map<String, String> pathParams) throws ServletException, IOException {
        UpgradeUtil.doUpgrade(this, request, response, sec, pathParams);
    }


    /**
     * Upgrade the HTTP connection represented by the {@code HttpServletRequest} and {@code HttpServletResponse} to the
     * WebSocket protocol and establish a WebSocket connection as per the provided {@link ServerEndpointConfig}.
     * <p>
     * This method is primarily intended to be used by frameworks that implement the front-controller pattern. It does
     * not deploy the provided endpoint.
     * <p>
     * If the WebSocket implementation is not deployed as part of a Jakarta Servlet container, this method will throw an
     * {@link UnsupportedOperationException}.
     * <p>
     * This method will be part of the Jakarta WebSocket API from version 2.1
     *
     * @param httpServletRequest  The {@code HttpServletRequest} to be processed as a WebSocket handshake as per section
     *                                4.0 of RFC 6455.
     * @param httpServletResponse The {@code HttpServletResponse} to be used when processing the
     *                                {@code httpServletRequest} as a WebSocket handshake as per section 4.0 of RFC
     *                                6455.
     * @param sec                 The server endpoint configuration to use to configure the WebSocket endpoint
     * @param pathParameters      Provides a mapping of path parameter names and values, if any, to be used for the
     *                                WebSocket connection established by the call to this method. If no such mapping is
     *                                defined, an empty Map must be passed.
     *
     * @throws IllegalStateException         if the provided request does not meet the requirements of the WebSocket
     *                                           handshake
     * @throws UnsupportedOperationException if the WebSocket implementation is not deployed as part of a Jakarta
     *                                           Servlet container
     * @throws IOException                   if an I/O error occurs during the establishment of a WebSocket connection
     * @throws DeploymentException           if a configuration error prevents the establishment of a WebSocket
     *                                           connection
     */
    public void upgradeHttpToWebSocket(Object httpServletRequest, Object httpServletResponse, ServerEndpointConfig sec,
            Map<String, String> pathParameters) throws IOException, DeploymentException {
        try {
            UpgradeUtil.doUpgrade(this, (HttpServletRequest) httpServletRequest,
                    (HttpServletResponse) httpServletResponse, sec, pathParameters);
        } catch (ServletException e) {
            throw new DeploymentException(e.getMessage(), e);
        }
    }


    public WsMappingResult findMapping(String path) {

        // Prevent registering additional endpoints once the first attempt has
        // been made to use one
        if (addAllowed) {
            addAllowed = false;
        }

        // Check an exact match. Simple case as there are no templates.
        ExactPathMatch match = configExactMatchMap.get(path);
        if (match != null) {
            return new WsMappingResult(match.getConfig(), Collections.emptyMap());
        }

        // No exact match. Need to look for template matches.
        UriTemplate pathUriTemplate;
        try {
            pathUriTemplate = new UriTemplate(path);
        } catch (DeploymentException e) {
            // Path is not valid so can't be matched to a WebSocketEndpoint
            return null;
        }

        // Number of segments has to match
        Integer key = Integer.valueOf(pathUriTemplate.getSegmentCount());
        ConcurrentSkipListMap<String, TemplatePathMatch> templateMatches = configTemplateMatchMap.get(key);

        if (templateMatches == null) {
            // No templates with an equal number of segments so there will be
            // no matches
            return null;
        }

        // List is in alphabetical order of normalised templates.
        // Correct match is the first one that matches.
        ServerEndpointConfig sec = null;
        Map<String, String> pathParams = null;
        for (TemplatePathMatch templateMatch : templateMatches.values()) {
            pathParams = templateMatch.getUriTemplate().match(pathUriTemplate);
            if (pathParams != null) {
                sec = templateMatch.getConfig();
                break;
            }
        }

        if (sec == null) {
            // No match
            return null;
        }

        return new WsMappingResult(sec, pathParams);
    }


    @Deprecated
    public boolean isEnforceNoAddAfterHandshake() {
        return enforceNoAddAfterHandshake;
    }


    @Deprecated
    public void setEnforceNoAddAfterHandshake(boolean enforceNoAddAfterHandshake) {
        this.enforceNoAddAfterHandshake = enforceNoAddAfterHandshake;
    }


    protected WsWriteTimeout getTimeout() {
        return wsWriteTimeout;
    }


    /**
     * {@inheritDoc} Overridden to make it visible to other classes in this package.
     */
    @Override
    protected InstanceManager getInstanceManager(ClassLoader classLoader) {
        return super.getInstanceManager(classLoader);
    }


    /**
     * {@inheritDoc} Overridden to make it visible to other classes in this package.
     */
    @Override
    protected void registerSession(Object key, WsSession wsSession) {
        super.registerSession(key, wsSession);
        if (wsSession.isOpen() && wsSession.getUserPrincipal() != null && wsSession.getHttpSessionId() != null) {
            registerAuthenticatedSession(wsSession, wsSession.getHttpSessionId());
        }
    }


    /**
     * {@inheritDoc} Overridden to make it visible to other classes in this package.
     */
    @Override
    protected void unregisterSession(Object key, WsSession wsSession) {
        if (wsSession.getUserPrincipalInternal() != null && wsSession.getHttpSessionId() != null) {
            unregisterAuthenticatedSession(wsSession, wsSession.getHttpSessionId());
        }
        super.unregisterSession(key, wsSession);
    }


    private void registerAuthenticatedSession(WsSession wsSession, String httpSessionId) {
        Set<WsSession> wsSessions = authenticatedSessions.get(httpSessionId);
        if (wsSessions == null) {
            wsSessions = ConcurrentHashMap.newKeySet();
            authenticatedSessions.putIfAbsent(httpSessionId, wsSessions);
            wsSessions = authenticatedSessions.get(httpSessionId);
        }
        wsSessions.add(wsSession);
    }


    private void unregisterAuthenticatedSession(WsSession wsSession, String httpSessionId) {
        Set<WsSession> wsSessions = authenticatedSessions.get(httpSessionId);
        // wsSessions will be null if the HTTP session has ended
        if (wsSessions != null) {
            wsSessions.remove(wsSession);
        }
    }


    public void closeAuthenticatedSession(String httpSessionId) {
        Set<WsSession> wsSessions = authenticatedSessions.remove(httpSessionId);

        if (wsSessions != null && !wsSessions.isEmpty()) {
            for (WsSession wsSession : wsSessions) {
                try {
                    wsSession.close(AUTHENTICATED_HTTP_SESSION_CLOSED);
                } catch (IOException e) {
                    // Any IOExceptions during close will have been caught and the
                    // onError method called.
                }
            }
        }
    }


    private static void validateEncoders(Class<? extends Encoder>[] encoders, InstanceManager instanceManager)
            throws DeploymentException {

        for (Class<? extends Encoder> encoder : encoders) {
            // Need to instantiate encoder to ensure it is valid and that
            // deployment can be failed if it is not. The encoder is then
            // discarded immediately.
            Encoder instance;
            try {
                if (instanceManager == null) {
                    instance = encoder.getConstructor().newInstance();
                } else {
                    instance = (Encoder) instanceManager.newInstance(encoder);
                    instanceManager.destroyInstance(instance);
                }
            } catch (ReflectiveOperationException | NamingException e) {
                throw new DeploymentException(sm.getString("serverContainer.encoderFail", encoder.getName()), e);
            }
        }
    }


    private static class TemplatePathMatch {
        private final ServerEndpointConfig config;
        private final UriTemplate uriTemplate;
        private final boolean fromAnnotatedPojo;

        TemplatePathMatch(ServerEndpointConfig config, UriTemplate uriTemplate, boolean fromAnnotatedPojo) {
            this.config = config;
            this.uriTemplate = uriTemplate;
            this.fromAnnotatedPojo = fromAnnotatedPojo;
        }


        public ServerEndpointConfig getConfig() {
            return config;
        }


        public UriTemplate getUriTemplate() {
            return uriTemplate;
        }


        public boolean isFromAnnotatedPojo() {
            return fromAnnotatedPojo;
        }
    }


    private static class ExactPathMatch {
        private final ServerEndpointConfig config;
        private final boolean fromAnnotatedPojo;

        ExactPathMatch(ServerEndpointConfig config, boolean fromAnnotatedPojo) {
            this.config = config;
            this.fromAnnotatedPojo = fromAnnotatedPojo;
        }


        public ServerEndpointConfig getConfig() {
            return config;
        }


        public boolean isFromAnnotatedPojo() {
            return fromAnnotatedPojo;
        }
    }
}
