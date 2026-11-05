/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.IOException;
import java.io.Serial;
import java.util.Optional;
import java.util.function.Function;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

/**
 * A servlet filter that provides a unique identifier to requests for a given session.
 * @author Paul Ferraro
 * @param <T> the session attribute type
 */
public abstract class AbstractRequestIdentifierFilter<T> extends HttpFilter implements RequestIdentifierProvider {
	/** The identifier for requests with no or newly associated sessions. */
	static final Integer INITIAL_REQUEST_IDENTIFIER = 0;

	@Serial
	private static final long serialVersionUID = -4671914387598405965L;
	/** The session attribute type */
	private final Class<T> type;

	AbstractRequestIdentifierFilter(Class<T> type) {
		this.type = type;
	}

	@Override
	protected void doFilter​(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
		HttpSession session = request.getSession(false);
		Integer requestId = Optional.ofNullable(session).map(!request.getMethod().equals("HEAD") ? this::getCurrentRequestId : this::getPreviousRequestId).orElse(INITIAL_REQUEST_IDENTIFIER);
		request.setAttribute(REQUEST_ID, requestId);
		// Record to response header for testing
		response.setIntHeader(REQUEST_ID, requestId);
		chain.doFilter(request, response);
	}

	/**
	 * Returns the request identifier for the current request from the specified session, if one exists.
	 * @param session the current session
	 * @return the request identifier for the current request from the specified session, if one exists.
	 */
	T readRequestId(HttpSession session) {
		return this.type.cast(session.getAttribute(REQUEST_ID));
	}

	/**
	 * Writes the specified request identifier to the session
	 * @param session the existing session
	 * @param requestId the current request identifier
	 */
	void writeRequestId(HttpSession session, T requestId) {
		session.setAttribute(REQUEST_ID, requestId);
	}

	/**
	 * Returns the next request identifier given the previous identifier.
	 * @param requestId the previous identifier
	 * @return the next request identifier given the previous identifier.
	 */
	abstract Integer next(T requestId);

	/**
	 * Returns the request identifier from its session storage type.
	 * @param requestId a request identifier in its session storage type
	 * @return the request identifier
	 */
	abstract Integer current(T requestId);

	/**
	 * Returns the request identifier for the previous request, using the previous identifier from the specified session.
	 * @param session the current session
	 * @return the request identifier for the previous request, using the previous identifier from the specified session.
	 */
	Integer getPreviousRequestId(HttpSession session) {
		return this.current(this.readRequestId(session));
	}

	/**
	 * Returns the request identifier for the current request, using the previous identifier from the specified session.
	 * @param session the current session
	 * @return the request identifier for the current request, using the previous identifier from the specified session.
	 */
	Integer getCurrentRequestId(HttpSession session) {
		return this.next(this.readRequestId(session));
	}

	static class RequestCountSessionListener<T> implements HttpSessionListener {
		private final Function<Integer, T> mapper;

		RequestCountSessionListener(Function<Integer, T> mapper) {
			this.mapper = mapper;
		}

		@Override
		public void sessionCreated(HttpSessionEvent event) {
			event.getSession().setAttribute(REQUEST_ID, this.mapper.apply(INITIAL_REQUEST_ID));
		}
	}
}
