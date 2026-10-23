/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.IOException;
import java.io.Serial;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * A servlet filter that provides a unique identifier to requests for a given session.
 * @author Paul Ferraro
 */
public class RequestIdentifierFilter extends HttpFilter implements RequestIdentifierProvider {
	/** The identifier for requests with no or newly associated sessions. */
	static final Integer INITIAL_REQUEST_IDENTIFIER = 0;

	@Serial
	private static final long serialVersionUID = -4671914387598405965L;

	private final transient RequestIdentifierManager manager;

	RequestIdentifierFilter(RequestIdentifierManager manager) {
		this.manager = manager;
	}

	@Override
	protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
		try {
			HttpSession session = request.getSession(false);
			// If HEAD request, use identifier of previous request
			Integer requestId = Optional.ofNullable(session).map(request.getMethod().equals("HEAD") ? this.manager::getPreviousRequestIdentifier : this.manager::getCurrentRequestIdentifier).orElse(INITIAL_REQUEST_IDENTIFIER);
			// Make request identifier available to servlets via request attribute
			request.setAttribute(RequestIdentifierProvider.REQUEST_IDENTIFIER, requestId);

			chain.doFilter(request, response);
		} catch (IOException | ServletException | RuntimeException | Error e) {
			e.printStackTrace();
			throw e;
		} finally {
			HttpSession session = request.getSession(false);
			if (session.isNew()) {
				this.manager.recordInitialRequestIdentifier(session, this.getRequestId(request));
			}
		}
	}
}
