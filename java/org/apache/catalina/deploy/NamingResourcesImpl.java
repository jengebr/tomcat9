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
package org.apache.catalina.deploy;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.NamingException;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.JmxEnabled;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.mbeans.MBeanUtils;
import org.apache.catalina.util.Introspection;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.naming.ContextBindings;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.descriptor.web.ContextEjb;
import org.apache.tomcat.util.descriptor.web.ContextEnvironment;
import org.apache.tomcat.util.descriptor.web.ContextLocalEjb;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.apache.tomcat.util.descriptor.web.ContextResourceEnvRef;
import org.apache.tomcat.util.descriptor.web.ContextResourceLink;
import org.apache.tomcat.util.descriptor.web.ContextService;
import org.apache.tomcat.util.descriptor.web.ContextTransaction;
import org.apache.tomcat.util.descriptor.web.InjectionTarget;
import org.apache.tomcat.util.descriptor.web.MessageDestinationRef;
import org.apache.tomcat.util.descriptor.web.NamingResources;
import org.apache.tomcat.util.descriptor.web.ResourceBase;
import org.apache.tomcat.util.res.StringManager;


/**
 * Holds and manages the naming resources defined in the J2EE Enterprise Naming Context and their associated JNDI
 * context.
 *
 * @author Remy Maucherat
 */
public class NamingResourcesImpl extends LifecycleMBeanBase implements Serializable, NamingResources {

    private static final long serialVersionUID = 1L;

    private static final Log log = LogFactory.getLog(NamingResourcesImpl.class);

    private static final StringManager sm = StringManager.getManager(NamingResourcesImpl.class);

    private volatile boolean resourceRequireExplicitRegistration = false;

    // ----------------------------------------------------------- Constructors


    /**
     * Create a new NamingResources instance.
     */
    public NamingResourcesImpl() {
        // NOOP
    }


    // ----------------------------------------------------- Instance Variables


    /**
     * Associated container object.
     */
    private Object container = null;


    /**
     * Set of naming entries, keyed by name.
     */
    private final Set<String> entries = new HashSet<>();


    /**
     * The EJB resource references for this web application, keyed by name.
     */
    private final Map<String,ContextEjb> ejbs = new HashMap<>();


    /**
     * The environment entries for this web application, keyed by name.
     */
    private final Map<String,ContextEnvironment> envs = new HashMap<>();


    /**
     * The local EJB resource references for this web application, keyed by name.
     */
    private final Map<String,ContextLocalEjb> localEjbs = new HashMap<>();


    /**
     * The message destination references for this web application, keyed by name.
     */
    private final Map<String,MessageDestinationRef> mdrs = new HashMap<>();


    /**
     * The resource environment references for this web application, keyed by name.
     */
    private final HashMap<String,ContextResourceEnvRef> resourceEnvRefs = new HashMap<>();


    /**
     * The resource references for this web application, keyed by name.
     */
    private final HashMap<String,ContextResource> resources = new HashMap<>();


    /**
     * The resource links for this web application, keyed by name.
     */
    private final HashMap<String,ContextResourceLink> resourceLinks = new HashMap<>();


    /**
     * The web service references for this web application, keyed by name.
     */
    private final HashMap<String,ContextService> services = new HashMap<>();


    /**
     * The transaction for this webapp.
     */
    private ContextTransaction transaction = null;


    /**
     * The property change support for this component.
     */
    protected final PropertyChangeSupport support = new PropertyChangeSupport(this);


    // ------------------------------------------------------------- Properties


    @Override
    public Object getContainer() {
        return container;
    }


    /**
     * Set the container with which the naming resources are associated.
     *
     * @param container the associated with the resources
     */
    public void setContainer(Object container) {
        this.container = container;
    }


    /**
     * Set the transaction object.
     *
     * @param transaction the transaction descriptor
     */
    public void setTransaction(ContextTransaction transaction) {
        this.transaction = transaction;
    }


    /**
     * @return the transaction object.
     */
    public ContextTransaction getTransaction() {
        return transaction;
    }


    /**
     * Add an EJB resource reference for this web application.
     *
     * @param ejb New EJB resource reference
     */
    public void addEjb(ContextEjb ejb) {

        // Entries with lookup-name and ejb-link are an error (EE.5.5.2 / EE.5.5.3)
        String ejbLink = ejb.getLink();
        String lookupName = ejb.getLookupName();

        if (ejbLink != null && !ejbLink.isEmpty() && lookupName != null && !lookupName.isEmpty()) {
            throw new IllegalArgumentException(sm.getString("namingResources.ejbLookupLink", ejb.getName()));
        }

        if (entries.contains(ejb.getName())) {
            return;
        } else {
            entries.add(ejb.getName());
        }

        synchronized (ejbs) {
            ejb.setNamingResources(this);
            ejbs.put(ejb.getName(), ejb);
        }
        support.firePropertyChange("ejb", null, ejb);

    }


    @Override
    public void addEnvironment(ContextEnvironment environment) {

        if (entries.contains(environment.getName())) {
            ContextEnvironment ce = findEnvironment(environment.getName());
            ContextResourceLink rl = findResourceLink(environment.getName());
            if (ce != null) {
                if (ce.getOverride()) {
                    removeEnvironment(environment.getName());
                } else {
                    return;
                }
            } else if (rl != null) {
                // Link. Need to look at the global resources
                NamingResourcesImpl global = getServer().getGlobalNamingResources();
                if (global.findEnvironment(rl.getGlobal()) != null) {
                    if (global.findEnvironment(rl.getGlobal()).getOverride()) {
                        removeResourceLink(environment.getName());
                    } else {
                        return;
                    }
                }
            } else {
                // It exists but it isn't an env or a res link...
                return;
            }
        }

        List<InjectionTarget> injectionTargets = environment.getInjectionTargets();
        String value = environment.getValue();
        String lookupName = environment.getLookupName();

        // Entries with injection targets but no value are effectively ignored
        if (injectionTargets != null && !injectionTargets.isEmpty() && (value == null || value.isEmpty())) {
            return;
        }

        // Entries with lookup-name and value are an error (EE.5.4.1.3)
        if (value != null && !value.isEmpty() && lookupName != null && !lookupName.isEmpty()) {
            throw new IllegalArgumentException(
                    sm.getString("namingResources.envEntryLookupValue", environment.getName()));
        }

        if (!checkResourceType(environment)) {
            throw new IllegalArgumentException(
                    sm.getString("namingResources.resourceTypeFail", environment.getName(), environment.getType()));
        }

        entries.add(environment.getName());

        synchronized (envs) {
            environment.setNamingResources(this);
            envs.put(environment.getName(), environment);
        }
        support.firePropertyChange("environment", null, environment);

        // Register with JMX
        if (resourceRequireExplicitRegistration) {
            try {
                MBeanUtils.createMBean(environment);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanCreateFail", environment.getName()), e);
            }
        }
    }

    // Container should be an instance of Server or Context. If it is anything
    // else, return null which will trigger a NPE.
    private Server getServer() {
        if (container instanceof Server) {
            return (Server) container;
        }
        if (container instanceof Context) {
            // Could do this in one go. Lots of casts so split out for clarity
            Engine engine = (Engine) ((Context) container).getParent().getParent();
            return engine.getService().getServer();
        }
        return null;
    }

    /**
     * Add a local EJB resource reference for this web application.
     *
     * @param ejb New EJB resource reference
     */
    public void addLocalEjb(ContextLocalEjb ejb) {

        if (entries.contains(ejb.getName())) {
            return;
        } else {
            entries.add(ejb.getName());
        }

        synchronized (localEjbs) {
            ejb.setNamingResources(this);
            localEjbs.put(ejb.getName(), ejb);
        }
        support.firePropertyChange("localEjb", null, ejb);

    }


    /**
     * Add a message destination reference for this web application.
     *
     * @param mdr New message destination reference
     */
    public void addMessageDestinationRef(MessageDestinationRef mdr) {

        if (entries.contains(mdr.getName())) {
            return;
        } else {
            if (!checkResourceType(mdr)) {
                throw new IllegalArgumentException(
                        sm.getString("namingResources.resourceTypeFail", mdr.getName(), mdr.getType()));
            }
            entries.add(mdr.getName());
        }

        synchronized (mdrs) {
            mdr.setNamingResources(this);
            mdrs.put(mdr.getName(), mdr);
        }
        support.firePropertyChange("messageDestinationRef", null, mdr);

    }


    /**
     * Add a property change listener to this component.
     *
     * @param listener The listener to add
     */
    public void addPropertyChangeListener(PropertyChangeListener listener) {

        support.addPropertyChangeListener(listener);

    }


    @Override
    public void addResource(ContextResource resource) {

        if (entries.contains(resource.getName())) {
            return;
        } else {
            if (!checkResourceType(resource)) {
                throw new IllegalArgumentException(
                        sm.getString("namingResources.resourceTypeFail", resource.getName(), resource.getType()));
            }
            entries.add(resource.getName());
        }

        synchronized (resources) {
            resource.setNamingResources(this);
            resources.put(resource.getName(), resource);
        }
        support.firePropertyChange("resource", null, resource);

        // Register with JMX
        if (resourceRequireExplicitRegistration) {
            try {
                MBeanUtils.createMBean(resource);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanCreateFail", resource.getName()), e);
            }
        }
    }


    /**
     * Add a resource environment reference for this web application.
     *
     * @param resource The resource
     */
    public void addResourceEnvRef(ContextResourceEnvRef resource) {

        if (entries.contains(resource.getName())) {
            return;
        } else {
            if (!checkResourceType(resource)) {
                throw new IllegalArgumentException(
                        sm.getString("namingResources.resourceTypeFail", resource.getName(), resource.getType()));
            }
            entries.add(resource.getName());
        }

        synchronized (resourceEnvRefs) {
            resource.setNamingResources(this);
            resourceEnvRefs.put(resource.getName(), resource);
        }
        support.firePropertyChange("resourceEnvRef", null, resource);

    }


    @Override
    public void addResourceLink(ContextResourceLink resourceLink) {

        if (entries.contains(resourceLink.getName())) {
            return;
        } else {
            entries.add(resourceLink.getName());
        }

        synchronized (resourceLinks) {
            resourceLink.setNamingResources(this);
            resourceLinks.put(resourceLink.getName(), resourceLink);
        }
        support.firePropertyChange("resourceLink", null, resourceLink);

        // Register with JMX
        if (resourceRequireExplicitRegistration) {
            try {
                MBeanUtils.createMBean(resourceLink);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanCreateFail", resourceLink.getName()), e);
            }
        }
    }


    /**
     * Add a web service reference for this web application.
     *
     * @param service New web service reference
     */
    public void addService(ContextService service) {

        if (entries.contains(service.getName())) {
            return;
        } else {
            entries.add(service.getName());
        }

        synchronized (services) {
            service.setNamingResources(this);
            services.put(service.getName(), service);
        }
        support.firePropertyChange("service", null, service);

    }


    /**
     * @return the EJB resource reference with the specified name, if any; otherwise, return <code>null</code>.
     *
     * @param name Name of the desired EJB resource reference
     */
    public ContextEjb findEjb(String name) {

        synchronized (ejbs) {
            return ejbs.get(name);
        }

    }


    /**
     * @return the defined EJB resource references for this application. If there are none, a zero-length array is
     *             returned.
     */
    public ContextEjb[] findEjbs() {

        synchronized (ejbs) {
            return ejbs.values().toArray(new ContextEjb[0]);
        }

    }


    /**
     * @return the environment entry with the specified name, if any; otherwise, return <code>null</code>.
     *
     * @param name Name of the desired environment entry
     */
    public ContextEnvironment findEnvironment(String name) {

        synchronized (envs) {
            return envs.get(name);
        }

    }


    /**
     * @return the array of defined environment entries for this web application. If none have been defined, a zero-length
     *             array is returned.
     */
    public ContextEnvironment[] findEnvironments() {

        synchronized (envs) {
            return envs.values().toArray(new ContextEnvironment[0]);
        }

    }


    /**
     * @return the local EJB resource reference with the specified name, if any; otherwise, return <code>null</code>.
     *
     * @param name Name of the desired EJB resource reference
     */
    public ContextLocalEjb findLocalEjb(String name) {

        synchronized (localEjbs) {
            return localEjbs.get(name);
        }

    }


    /**
     * @return the defined local EJB resource references for this application. If there are none, a zero-length array is
     *             returned.
     */
    public ContextLocalEjb[] findLocalEjbs() {

        synchronized (localEjbs) {
            return localEjbs.values().toArray(new ContextLocalEjb[0]);
        }

    }


    /**
     * @return the message destination reference with the specified name, if any; otherwise, return <code>null</code>.
     *
     * @param name Name of the desired message destination reference
     */
    public MessageDestinationRef findMessageDestinationRef(String name) {

        synchronized (mdrs) {
            return mdrs.get(name);
        }

    }


    /**
     * @return the defined message destination references for this application. If there are none, a zero-length array
     *             is returned.
     */
    public MessageDestinationRef[] findMessageDestinationRefs() {

        synchronized (mdrs) {
            return mdrs.values().toArray(new MessageDestinationRef[0]);
        }

    }


    /**
     * @return the resource reference with the specified name, if any; otherwise return <code>null</code>.
     *
     * @param name Name of the desired resource reference
     */
    public ContextResource findResource(String name) {

        synchronized (resources) {
            return resources.get(name);
        }

    }


    /**
     * @return the resource link with the specified name, if any; otherwise return <code>null</code>.
     *
     * @param name Name of the desired resource link
     */
    public ContextResourceLink findResourceLink(String name) {

        synchronized (resourceLinks) {
            return resourceLinks.get(name);
        }

    }


    /**
     * @return the defined resource links for this application. If none have been defined, a zero-length array is
     *             returned.
     */
    public ContextResourceLink[] findResourceLinks() {

        synchronized (resourceLinks) {
            return resourceLinks.values().toArray(new ContextResourceLink[0]);
        }

    }


    /**
     * @return the defined resource references for this application. If none have been defined, a zero-length array is
     *             returned.
     */
    public ContextResource[] findResources() {

        synchronized (resources) {
            return resources.values().toArray(new ContextResource[0]);
        }

    }


    /**
     * @return the resource environment reference type for the specified name, if any; otherwise return
     *             <code>null</code>.
     *
     * @param name Name of the desired resource environment reference
     */
    public ContextResourceEnvRef findResourceEnvRef(String name) {

        synchronized (resourceEnvRefs) {
            return resourceEnvRefs.get(name);
        }

    }


    /**
     * @return the array of resource environment reference names for this web application. If none have been specified, a
     *             zero-length array is returned.
     */
    public ContextResourceEnvRef[] findResourceEnvRefs() {

        synchronized (resourceEnvRefs) {
            return resourceEnvRefs.values().toArray(new ContextResourceEnvRef[0]);
        }

    }


    /**
     * @return the web service reference for the specified name, if any; otherwise return <code>null</code>.
     *
     * @param name Name of the desired web service
     */
    public ContextService findService(String name) {

        synchronized (services) {
            return services.get(name);
        }

    }


    /**
     * @return the defined web service references for this application. If none have been defined, a zero-length array
     *             is returned.
     */
    public ContextService[] findServices() {

        synchronized (services) {
            return services.values().toArray(new ContextService[0]);
        }

    }


    /**
     * Remove any EJB resource reference with the specified name.
     *
     * @param name Name of the EJB resource reference to remove
     */
    public void removeEjb(String name) {

        entries.remove(name);

        ContextEjb ejb;
        synchronized (ejbs) {
            ejb = ejbs.remove(name);
        }
        if (ejb != null) {
            support.firePropertyChange("ejb", ejb, null);
            ejb.setNamingResources(null);
        }

    }


    @Override
    public void removeEnvironment(String name) {

        entries.remove(name);

        ContextEnvironment environment;
        synchronized (envs) {
            environment = envs.remove(name);
        }
        if (environment != null) {
            support.firePropertyChange("environment", environment, null);
            // De-register with JMX
            if (resourceRequireExplicitRegistration) {
                try {
                    MBeanUtils.destroyMBean(environment);
                } catch (Exception e) {
                    log.warn(sm.getString("namingResources.mbeanDestroyFail", environment.getName()), e);
                }
            }
            environment.setNamingResources(null);
        }
    }


    /**
     * Remove any local EJB resource reference with the specified name.
     *
     * @param name Name of the EJB resource reference to remove
     */
    public void removeLocalEjb(String name) {

        entries.remove(name);

        ContextLocalEjb localEjb;
        synchronized (localEjbs) {
            localEjb = localEjbs.remove(name);
        }
        if (localEjb != null) {
            support.firePropertyChange("localEjb", localEjb, null);
            localEjb.setNamingResources(null);
        }

    }


    /**
     * Remove any message destination reference with the specified name.
     *
     * @param name Name of the message destination resource reference to remove
     */
    public void removeMessageDestinationRef(String name) {

        entries.remove(name);

        MessageDestinationRef mdr;
        synchronized (mdrs) {
            mdr = mdrs.remove(name);
        }
        if (mdr != null) {
            support.firePropertyChange("messageDestinationRef", mdr, null);
            mdr.setNamingResources(null);
        }

    }


    /**
     * Remove a property change listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removePropertyChangeListener(PropertyChangeListener listener) {

        support.removePropertyChangeListener(listener);

    }


    @Override
    public void removeResource(String name) {

        entries.remove(name);

        ContextResource resource;
        synchronized (resources) {
            resource = resources.remove(name);
        }
        if (resource != null) {
            support.firePropertyChange("resource", resource, null);
            // De-register with JMX
            if (resourceRequireExplicitRegistration) {
                try {
                    MBeanUtils.destroyMBean(resource);
                } catch (Exception e) {
                    log.warn(sm.getString("namingResources.mbeanDestroyFail", resource.getName()), e);
                }
            }
            resource.setNamingResources(null);
        }
    }


    /**
     * Remove any resource environment reference with the specified name.
     *
     * @param name Name of the resource environment reference to remove
     */
    public void removeResourceEnvRef(String name) {

        entries.remove(name);

        ContextResourceEnvRef resourceEnvRef;
        synchronized (resourceEnvRefs) {
            resourceEnvRef = resourceEnvRefs.remove(name);
        }
        if (resourceEnvRef != null) {
            support.firePropertyChange("resourceEnvRef", resourceEnvRef, null);
            resourceEnvRef.setNamingResources(null);
        }

    }


    @Override
    public void removeResourceLink(String name) {

        entries.remove(name);

        ContextResourceLink resourceLink;
        synchronized (resourceLinks) {
            resourceLink = resourceLinks.remove(name);
        }
        if (resourceLink != null) {
            support.firePropertyChange("resourceLink", resourceLink, null);
            // De-register with JMX
            if (resourceRequireExplicitRegistration) {
                try {
                    MBeanUtils.destroyMBean(resourceLink);
                } catch (Exception e) {
                    log.warn(sm.getString("namingResources.mbeanDestroyFail", resourceLink.getName()), e);
                }
            }
            resourceLink.setNamingResources(null);
        }
    }


    /**
     * Remove any web service reference with the specified name.
     *
     * @param name Name of the web service reference to remove
     */
    public void removeService(String name) {

        entries.remove(name);

        ContextService service;
        synchronized (services) {
            service = services.remove(name);
        }
        if (service != null) {
            support.firePropertyChange("service", service, null);
            service.setNamingResources(null);
        }

    }


    // ------------------------------------------------------- Lifecycle methods

    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal();

        // Set this before we register currently known naming resources to avoid
        // timing issues. Duplication registration is not an issue.
        resourceRequireExplicitRegistration = true;

        for (ContextResource cr : resources.values()) {
            try {
                MBeanUtils.createMBean(cr);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanCreateFail", cr.getName()), e);
            }
        }

        for (ContextEnvironment ce : envs.values()) {
            try {
                MBeanUtils.createMBean(ce);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanCreateFail", ce.getName()), e);
            }
        }

        for (ContextResourceLink crl : resourceLinks.values()) {
            try {
                MBeanUtils.createMBean(crl);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanCreateFail", crl.getName()), e);
            }
        }
    }


    @Override
    protected void startInternal() throws LifecycleException {
        fireLifecycleEvent(CONFIGURE_START_EVENT, null);
        setState(LifecycleState.STARTING);
    }


    @Override
    protected void stopInternal() throws LifecycleException {
        cleanUp();
        setState(LifecycleState.STOPPING);
        fireLifecycleEvent(CONFIGURE_STOP_EVENT, null);
    }

    /**
     * Close those resources that an explicit close may help clean-up faster.
     */
    private void cleanUp() {
        if (resources.isEmpty()) {
            return;
        }
        javax.naming.Context ctxt;
        try {
            if (container instanceof Server) {
                ctxt = ((Server) container).getGlobalNamingContext();
            } else {
                ctxt = ContextBindings.getClassLoader();
                ctxt = (javax.naming.Context) ctxt.lookup("comp/env");
            }
        } catch (NamingException e) {
            log.warn(sm.getString("namingResources.cleanupNoContext", container), e);
            return;
        }
        for (ContextResource cr : resources.values()) {
            if (cr.getSingleton()) {
                String closeMethod = cr.getCloseMethod();
                if (closeMethod != null && !closeMethod.isEmpty()) {
                    String name = cr.getName();
                    Object resource;
                    try {
                        resource = ctxt.lookup(name);
                    } catch (NamingException e) {
                        log.warn(sm.getString("namingResources.cleanupNoResource", cr.getName(), container), e);
                        continue;
                    }
                    cleanUp(resource, name, closeMethod);
                }
            }
        }
    }


    /**
     * Clean up a resource by calling the defined close method. For example, closing a database connection pool will
     * close it's open connections. This will happen on GC but that leaves db connections open that may cause issues.
     *
     * @param resource The resource to close.
     */
    private void cleanUp(Object resource, String name, String closeMethod) {
        // Look for a zero-arg close() method
        Method m;
        try {
            m = resource.getClass().getMethod(closeMethod, (Class<?>[]) null);
        } catch (SecurityException e) {
            log.debug(sm.getString("namingResources.cleanupCloseSecurity", closeMethod, name, container));
            return;
        } catch (NoSuchMethodException e) {
            log.debug(sm.getString("namingResources.cleanupNoClose", name, container, closeMethod));
            return;
        }
        try {
            m.invoke(resource, (Object[]) null);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            log.warn(sm.getString("namingResources.cleanupCloseFailed", closeMethod, name, container), e);
        } catch (InvocationTargetException e) {
            Throwable t = ExceptionUtils.unwrapInvocationTargetException(e);
            ExceptionUtils.handleThrowable(t);
            log.warn(sm.getString("namingResources.cleanupCloseFailed", closeMethod, name, container), t);
        }
    }

    @Override
    protected void destroyInternal() throws LifecycleException {

        // Set this before we de-register currently known naming resources to
        // avoid timing issues. Duplication de-registration is not an issue.
        resourceRequireExplicitRegistration = false;

        // Destroy in reverse order to create, although it should not matter
        for (ContextResourceLink crl : resourceLinks.values()) {
            try {
                MBeanUtils.destroyMBean(crl);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanDestroyFail", crl.getName()), e);
            }
        }

        for (ContextEnvironment ce : envs.values()) {
            try {
                MBeanUtils.destroyMBean(ce);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanDestroyFail", ce.getName()), e);
            }
        }

        for (ContextResource cr : resources.values()) {
            try {
                MBeanUtils.destroyMBean(cr);
            } catch (Exception e) {
                log.warn(sm.getString("namingResources.mbeanDestroyFail", cr.getName()), e);
            }
        }

        super.destroyInternal();
    }


    @Override
    protected String getDomainInternal() {
        // Use the same domain as our associated container if we have one
        Object c = getContainer();

        if (c instanceof JmxEnabled) {
            return ((JmxEnabled) c).getDomain();
        }

        return null;
    }


    @Override
    protected String getObjectNameKeyProperties() {
        Object c = getContainer();
        if (c instanceof Container) {
            return "type=NamingResources" + ((Container) c).getMBeanKeyProperties();
        }
        // Server or just unknown
        return "type=NamingResources";
    }

    /**
     * Checks that the configuration of the type for the specified resource is consistent with any injection targets and
     * if the type is not specified, tries to configure the type based on the injection targets
     *
     * @param resource The resource to check
     *
     * @return <code>true</code> if the type for the resource is now valid (if previously <code>null</code> this means
     *             it is now set) or <code>false</code> if the current resource type is inconsistent with the injection
     *             targets and/or cannot be determined
     */
    private boolean checkResourceType(ResourceBase resource) {
        if (!(container instanceof Context)) {
            // Only Context's will have injection targets
            return true;
        }

        if (resource.getInjectionTargets() == null || resource.getInjectionTargets().isEmpty()) {
            // No injection targets so use the defined type for the resource
            return true;
        }

        Context context = (Context) container;

        String typeName = resource.getType();
        Class<?> typeClass = null;
        if (typeName != null) {
            typeClass = Introspection.loadClass(context, typeName);
            if (typeClass == null) {
                // Can't load the type - will trigger a failure later so don't
                // fail here
                return true;
            }
        }

        Class<?> compatibleClass = getCompatibleType(context, resource, typeClass);
        if (compatibleClass == null) {
            // Indicates that a compatible type could not be identified that
            // worked for all injection targets
            return false;
        }

        resource.setType(compatibleClass.getCanonicalName());
        return true;
    }

    private Class<?> getCompatibleType(Context context, ResourceBase resource, Class<?> typeClass) {

        Class<?> result = null;

        for (InjectionTarget injectionTarget : resource.getInjectionTargets()) {
            Class<?> clazz = Introspection.loadClass(context, injectionTarget.getTargetClass());
            if (clazz == null) {
                // Can't load class - therefore ignore this target
                continue;
            }

            // Look for a match
            String targetName = injectionTarget.getTargetName();
            // Look for a setter match first
            Class<?> targetType = getSetterType(clazz, targetName);
            if (targetType == null) {
                // Try a field match if no setter match
                targetType = getFieldType(clazz, targetName);
            }
            if (targetType == null) {
                // No match - ignore this injection target
                continue;
            }
            targetType = Introspection.convertPrimitiveType(targetType);

            if (typeClass == null) {
                // Need to find a common type amongst the injection targets
                if (result == null) {
                    result = targetType;
                } else if (targetType.isAssignableFrom(result)) {
                    // NO-OP - This will work
                } else if (result.isAssignableFrom(targetType)) {
                    // Need to use more specific type
                    result = targetType;
                } else {
                    // Incompatible types
                    return null;
                }
            } else {
                // Each injection target needs to be consistent with the defined
                // type
                if (targetType.isAssignableFrom(typeClass)) {
                    result = typeClass;
                } else {
                    // Incompatible types
                    return null;
                }
            }
        }
        return result;
    }

    private Class<?> getSetterType(Class<?> clazz, String name) {
        Method[] methods = Introspection.getDeclaredMethods(clazz);
        if (methods != null && methods.length > 0) {
            for (Method method : methods) {
                if (Introspection.isValidSetter(method) && Introspection.getPropertyName(method).equals(name)) {
                    return method.getParameterTypes()[0];
                }
            }
        }
        return null;
    }

    private Class<?> getFieldType(Class<?> clazz, String name) {
        Field[] fields = Introspection.getDeclaredFields(clazz);
        if (fields != null && fields.length > 0) {
            for (Field field : fields) {
                if (field.getName().equals(name)) {
                    return field.getType();
                }
            }
        }
        return null;
    }
}
