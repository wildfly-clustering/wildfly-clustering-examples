/*
 * JBoss, Home of Professional Open Source
 * Copyright 2024, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.clustering.examples;

import org.jboss.ejb.client.*;
import org.jboss.logging.Logger;

import org.jboss.test.clusterbench.ejb.stateless.RemoteStatelessSB;
import org.jboss.test.clusterbench.ejb.stateless.RemoteStatelessSBImpl;

// import javax.naming.Context;
// import javax.naming.InitialContext;
// import java.util.Hashtable;

/**
 * This example client application demonstrates how to use EJB/HTTP to make invocations on a stateless session bean
 * RemoteStatelessSB, deployed on the two worker instances by the clusterbench deployment clusterbench-ee10.ear.
 *
 * The proxy is created using the EJBClient API and specifies the module, bean implementation and interface of the bean
 * to be invoked upon.
 *
 * Additionally, The wildfly-config.xml file includes an EJB client configuration section specifying a URL pointing to
 * the load balancer and including its HTTP invoker context path prefix. This allows the EJB/HTTP discovery mechanism
 * to find out which deployments are accessible via the load balancer.
 *
 */
public class RemoteEJBClient {
    private static final Logger log = Logger.getLogger(RemoteEJBClient.class);
    private static final String application = "clusterbench-ee10";
    private static final String module = "clusterbench-ee10-ejb";
    private static final String distinct = "";
    private static final String bean = "RemoteStatelessSBImpl";
    private static final String intf = RemoteStatelessSB.class.getName();
    // set up the EJB identifiers for the bean we invoke on
    private static EJBModuleIdentifier MODULE_IDENTIFIER = new EJBModuleIdentifier(application, module, distinct);
    private static EJBIdentifier EJB_IDENTIFIER = new EJBIdentifier(MODULE_IDENTIFIER, RemoteStatelessSBImpl.class.getSimpleName());
    private static RemoteStatelessSB remoteStatelessSBProxy = null;

    private static final String jndiName = "ejb:" + application + "/" + module + "/" + bean + "!" + intf;

    public static void main(String[] args) throws Exception {

        log.info("Creating SLSB using EJBClient API.");
        try {
            StatelessEJBLocator<RemoteStatelessSB> statelessEJBLocator = StatelessEJBLocator.create(RemoteStatelessSB.class, EJB_IDENTIFIER, Affinity.NONE);
            remoteStatelessSBProxy  = EJBClient.createProxy(statelessEJBLocator);
            Affinity strongAffinity = EJBClient.getStrongAffinity(remoteStatelessSBProxy);
            Affinity weakAffinity = EJBClient.getWeakAffinity(remoteStatelessSBProxy);

            log.infof("SLSB proxy created: %s (strong affinity %s, weak affinity %s)", remoteStatelessSBProxy, strongAffinity, weakAffinity);
        } catch (Exception e) {
            log.error("Error during SLSB proxy creation.", e);
        }

        /*

        // an alternative means to create the proxy
        log.info("Creating SLSB using Wildfly Naming client API .");
        final Hashtable<String, String> jndiProperties = new Hashtable<>();
        jndiProperties.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
        jndiProperties.put(Context.PROVIDER_URL, "http://localhost:8080/wildfly-services");
        final Context context = new InitialContext(jndiProperties);
        final RemoteStatelessSB remoteStatelessSBProxy = (RemoteStatelessSB) context.lookup(jndiName);

        */

        // Invoke on the stateless bean
        log.infof("Invoking method getNodeName(): result = %s", remoteStatelessSBProxy.getNodeName());
    }
}