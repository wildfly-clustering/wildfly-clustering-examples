/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015, Red Hat, Inc. and/or its affiliates, and individual
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

import org.jboss.logging.Logger;

import org.jboss.test.clusterbench.ejb.stateless.RemoteStatelessSB;
import javax.naming.Context;
import javax.naming.InitialContext;
import java.util.Hashtable;

/**
 * A client application which invokes on a stateless session bean RemoteStatelessSB found in the clusterbench
 * deployment clusterbench-ee10-ejb.ear
 */
public class RemoteEJBClient {
    private static final Logger log = Logger.getLogger(RemoteEJBClient.class);
    private static final String application = "clusterbench-ee10";
    private static final String module = "clusterbench-ee10-ejb";
    private static final String bean = "RemoteStatelessSBImpl";
    private static final String intf = RemoteStatelessSB.class.getName();

    private static final String jndiName = "ejb:" + application + "/" + module + "/" + bean + "!" + intf;

    public static void main(String[] args) throws Exception {

        // lookup the bean
        final Hashtable<String, String> jndiProperties = new Hashtable<>();
        jndiProperties.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
        jndiProperties.put(Context.PROVIDER_URL,"https://localhost:8443/wildfly-services");
        final Context context = new InitialContext(jndiProperties);
        final RemoteStatelessSB remoteStatelessSBProxy = (RemoteStatelessSB) context.lookup(jndiName);

        // Invoke on the stateless bean
        log.infof("Invoking method getNodeName(): result = %s", remoteStatelessSBProxy.getNodeName());
     }
 }
