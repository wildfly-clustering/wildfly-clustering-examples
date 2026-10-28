/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.IOException;
import java.io.Serial;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * A simple servlet that records the session identifier to the response header.
 * <dl>
 * <dt>GET/HEAD</dt>
 * <dd>Records session identifier to response header, if one exists</dd>
 * <dt>PUT</dt>
 * <dd>Records session identifier to response header, creating if necessary</dd>
 * <dt>DELETE</dt>
 * <dd>Invalidates session, if one exists</dd>
 * </dl>
 * @author Paul Ferraro
 */
@WebServlet(urlPatterns = SessionServlet.PATH)
public class SessionServlet extends HttpServlet implements RequestIdentifierProvider {
	static final String NAME = "session";
	static final String PATH = "/" + NAME;
	static final String SESSION = "session-id";

	@Serial
	private static final long serialVersionUID = -5189993114817119720L;

	/** Default constructor */
	public SessionServlet() {
		// Do nothing
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.addIntHeader(REQUEST_IDENTIFIER, this.getRequestId(request));
		super.service(request, response);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			response.addHeader(SESSION, session.getId());
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(true);
		response.addHeader(SESSION, session.getId());
	}

	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			response.addHeader(SESSION, session.getId());
			session.invalidate();
		}
	}
}
