/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.examples.mod_cluster;

import java.util.Optional;
import java.util.Properties;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.EJBClient;
import org.jboss.ejb.client.EJBIdentifier;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.test.clusterbench.ejb.stateless.RemoteStatelessSB;

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
 */
public class RemoteEJBClient {
	private static final System.Logger LOGGER = System.getLogger(RemoteEJBClient.class.getName());

	private static final String LOCATOR_PROPERTY = "ejb.locator";

	private static final String APPLICATION_NAME = "clusterbench-ee10";
	private static final String MODULE_NAME = "clusterbench-ee10-ejb";

	private RemoteEJBClient() {
		// Hide
	}

	/**
	 * Main method.
	 * @param args ignored
	 */
	public static void main(String... args) {
		try (Locator locator = Optional.ofNullable(System.getProperty(LOCATOR_PROPERTY)).map(String::toUpperCase).map(EjbLocator::valueOf).orElse(EjbLocator.EJB_CLIENT)) {

			LOGGER.log(System.Logger.Level.INFO, "Creating SLSB proxy via {0}", locator);

			RemoteStatelessSB proxy = locator.createSessionBean("RemoteStatelessSBImpl", RemoteStatelessSB.class);

			LOGGER.log(System.Logger.Level.INFO, "Invoking method getNodeName(): result = {0}", proxy.getNodeName());
		}
	}

	interface Locator extends AutoCloseable {
		default <T> T createSessionBean(Class<? extends T> beanClass, Class<T> remoteInterface) {
			return this.createSessionBean(beanClass.getSimpleName(), remoteInterface);
		}

		<T> T createSessionBean(String beanName, Class<T> remoteInterface);

		@Override
		default void close() {
			// Do nothing
		}
	}

	enum EjbLocator implements Locator {
		EJB_CLIENT() {
			@Override
			public <T> T createSessionBean(String beanName, Class<T> remoteInterface) {
				EJBModuleIdentifier moduleId = new EJBModuleIdentifier(APPLICATION_NAME, MODULE_NAME, "");
				EJBIdentifier beanId = new EJBIdentifier(moduleId, beanName);
				StatelessEJBLocator<T> locator = StatelessEJBLocator.create(remoteInterface, beanId, Affinity.NONE);
				T proxy = EJBClient.createProxy(locator);
				Affinity strongAffinity = EJBClient.getStrongAffinity(proxy);
				Affinity weakAffinity = EJBClient.getWeakAffinity(proxy);
				LOGGER.log(System.Logger.Level.INFO, "SLSB proxy created: {0} (strong affinity {1}, weak affinity {2})", proxy, strongAffinity, weakAffinity);
				return proxy;
			}
		},
		JNDI() {
			private final Context context = this.createContext();

			private Context createContext() {
				Properties properties = new Properties();
				properties.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
				properties.put(Context.PROVIDER_URL, "http://localhost:8080/wildfly-services");
				try {
					return new InitialContext(properties);
				} catch (NamingException e) {
					throw new IllegalArgumentException(properties.toString(), e);
				}
			}

			@Override
			public <T> T createSessionBean(String beanName, Class<T> remoteInterface) {
				String jndiName = String.format("ejb:%s/%s/%s!%s", APPLICATION_NAME, MODULE_NAME, beanName, remoteInterface.getName());
				try {
					return remoteInterface.cast(this.context.lookup(jndiName));
				} catch (NamingException e) {
					throw new IllegalArgumentException(jndiName, e);
				}
			}

			@Override
			public void close() {
				try {
					this.context.close();
				} catch (NamingException e) {
					throw new IllegalArgumentException(e);
				}
			}
		},
	}
}
