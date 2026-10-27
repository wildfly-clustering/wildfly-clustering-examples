/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.session.container.servlet.examples;

import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import jakarta.servlet.http.HttpFilter;

import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.wildfly.clustering.arquillian.AbstractITCase;
import org.wildfly.clustering.arquillian.DeploymentContainer;
import org.wildfly.clustering.container.ContainerLifecycle;

/**
 * @author Paul Ferraro
 */
public class RequestIdentifierITCase extends AbstractITCase<Class<? extends HttpFilter>, WebArchive> {

	static {
		// OpenLiberty's remote arquillian container is hard-coded to use HTTPS
		// To avoid certificate importation, we will workaround by trusting everything
		if (Boolean.getBoolean("test.trust-all-certs")) {
			try {
				SSLContext context = SSLContext.getInstance("TLS");
				context.init(null, new TrustManager[] { new X509TrustManager() {
					@Override
					public X509Certificate[] getAcceptedIssuers() {
						return null;
					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType) {
					}

					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType) {
					}
				}}, null);
				SSLContext.setDefault(context);
			} catch (NoSuchAlgorithmException | KeyManagementException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	@RegisterExtension
	static final ArquillianExtension ARQUILLIAN = new ArquillianExtension();
	static final String FILTER = "test.filter";
	static final String SERVERS = "test.servers";
	static final String CLIENTS = "test.clients";
	static final String REQUESTS = "test.requests";
	static final String THREADS = "test.threads";
	static final String INTERVAL = "test.interval";
	static final String FAILOVER_RATIO = "test.failover-ratio";

	enum Filter implements Supplier<Class<? extends HttpFilter>> {
		NON_ATOMIC(NoMutexRequestIdentifierFilter.class),
		SESSION_MUTEX(SessionMutexRequestIdentifierFilter.class),
		SESSION_ID_MUTEX(SessionIdentifierMutexRequestIdentifierFilter.class),
		SESSION_ATTRIBUTE_MUTEX(SessionAttributeMutexRequestIdentifierFilter.class),
		ATOMIC(AtomicIntegerRequestIdentifierFilter.class),
		ATOMIC_RESET(ResetAtomicIntegerRequestIdentifierFilter.class),
		;
		private Class<? extends HttpFilter> filterClass;

		Filter(Class<? extends HttpFilter> filterClass) {
			this.filterClass = filterClass;
		}

		@Override
		public Class<? extends HttpFilter> get() {
			return this.filterClass;
		}
	}

	private static final RequestIdentifierTester.Configuration CONFIGURATION = new RequestIdentifierTester.Configuration() {
		private final int clients = Integer.getInteger(CLIENTS, 1);
		private final int requests = Integer.getInteger(REQUESTS, 100);
		private final int threads = Integer.getInteger(THREADS, 0);
		private final Duration interval = Optional.ofNullable(System.getProperty(INTERVAL)).map(Duration::parse).orElse(Duration.ZERO);
		private final double failoverRatio = Optional.ofNullable(System.getProperty(FAILOVER_RATIO)).map(Double::valueOf).orElse(0d);

		@Override
		public int getClients() {
			return this.clients;
		}

		@Override
		public int getRequests() {
			return this.requests;
		}

		@Override
		public int getThreads() {
			return this.threads;
		}

		@Override
		public Duration getInterval() {
			return this.interval;
		}

		@Override
		public double getFailoverRatio() {
			return this.failoverRatio;
		}
	};

	private final Class<? extends HttpFilter> filterClass;
	private final int clusterSize;

	public RequestIdentifierITCase() {
		super(() -> new RequestIdentifierTester(CONFIGURATION));
		assertThat(CONFIGURATION.getClients()).as(CLIENTS).isGreaterThanOrEqualTo(1);
		assertThat(CONFIGURATION.getRequests()).as(REQUESTS).isGreaterThan(0);
		assertThat(CONFIGURATION.getThreads()).as(THREADS).isGreaterThanOrEqualTo(0);
		assertThat(CONFIGURATION.getInterval()).as(INTERVAL).isGreaterThanOrEqualTo(Duration.ZERO);
		assertThat(CONFIGURATION.getFailoverRatio()).isBetween(0d, 1d);
		Filter filter = Optional.ofNullable(System.getProperty(FILTER)).map(Filter::valueOf).orElse(Filter.ATOMIC_RESET);
		this.filterClass = filter.get();
		this.clusterSize = Integer.getInteger(SERVERS, 1);
		assertThat(this.clusterSize).isBetween(1, 4);
		System.out.println(String.format("Running distributed request identifier test using filter = %s, servers = %d, clients = %d, requests = %d, threads = %d, interval = %s, failover-ratio = %f", filter, this.clusterSize, CONFIGURATION.getClients(), CONFIGURATION.getRequests(), CONFIGURATION.getThreads(), CONFIGURATION.getInterval(), CONFIGURATION.getFailoverRatio()));
	}

	@Override
	public WebArchive createArchive(Class<? extends HttpFilter> filter) {
		WebArchive archive = ShrinkWrap.create(WebArchive.class, filter.getSimpleName() + ".war")
				.addClasses(SessionServlet.class, RequestIdentifierManager.class, RequestIdentifierProvider.class)
				;
		// Add servlet class (and its superclasses)
		Class<?> applicationClass = filter;
		while (applicationClass != HttpFilter.class) {
			archive.addClass(applicationClass);
			applicationClass = applicationClass.getSuperclass();
		}
		// Add a container-specific deployment descriptor, if necessary
		String containerResourceName = String.format("%s-web.xml", System.getProperty("test.container"));
		URL containerResource = filter.getClassLoader().getResource(containerResourceName);
		if (containerResource != null) {
			archive.addAsWebInfResource(containerResource, containerResourceName);
		}
		// Add any auxiliary resources
		File webResources = Path.of(System.getProperty("test.resources")).toFile();
		if (webResources.exists()) {
			for (File webResource : webResources.listFiles()) {
				if (webResource.isFile()) {
					archive.addAsWebInfResource(webResource);
				}
			}
		}
		// Add any auxiliary libraries
		File libDirectory = Path.of(System.getProperty("test.lib")).toFile();
		if (libDirectory.exists()) {
			for (File lib : libDirectory.listFiles((dir, name) -> name.endsWith(".jar"))) {
				archive.addAsLibrary(lib);
			}
		}
		// Output contents of WAR, for verification
		System.out.println(archive.toString(true));
		return archive;
	}

	@Override
	public List<DeploymentContainer> getDeploymentContainers() {
		return super.getDeploymentContainers().subList(0, this.clusterSize);
	}

	@RunAsClient
	@Test
	void test() {
		try (ContainerLifecycle container = ContainerLifecycle.from(System.getProperties())) {
			container.start();
			this.accept(this.filterClass);
			container.stop();
		}
	}
}
