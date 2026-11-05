/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.Serial;

import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

/**
 * A request identifier filter that performs thread-safe reads/writes to the session using the monitor of an HttpSession attribute.
 * This implementation is safe for use by concurrent requests for the same session, on any spec-compliant servlet container.
 * @author Paul Ferraro
 */
@WebFilter(urlPatterns = "/*")
public class SessionAttributeMutexRequestIdentifierFilter extends AbstractIntegerRequestIdentifierFilter implements ServletContextListener {
	@Serial
	private static final long serialVersionUID = -2687109660865258448L;
	private static final String REQUEST_ID_MUTEX = "x-request-id-mutex";

	/** Default constructor */
	public SessionAttributeMutexRequestIdentifierFilter() {
		// Do nothing
	}

	@Override
	Integer getCurrentRequestId(HttpSession session) {
		Object mutex = session.getAttribute(REQUEST_ID_MUTEX);
		synchronized (mutex) {
			return super.getCurrentRequestId(session);
		}
	}

	/**
	 * A session listener that stores a mutex for new sessions.
	 */
	@WebListener
	public static class MutexSessionListener implements HttpSessionListener {
		/** Default constructor */
		public MutexSessionListener() {
			// Do nothing
		}

		@Override
		public void sessionCreated(HttpSessionEvent event) {
			event.getSession().setAttribute(REQUEST_ID_MUTEX, new byte[0]);
		}
	}
}
