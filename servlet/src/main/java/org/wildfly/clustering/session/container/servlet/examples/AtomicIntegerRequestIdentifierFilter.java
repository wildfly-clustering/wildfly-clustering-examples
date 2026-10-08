/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import jakarta.servlet.annotation.WebFilter;

/**
 * A servlet filter that provides a unique identifier to requests for a given session via a mutable AtomicInteger session attribute.
 * While this implementation is safe for use by concurrent requests for the same session, the implementation is not portable across <em>distributed</em> container implementations.
 * <br/>
 * §7.7.1 of the Jakarta Servlet specification states:
 * <blockquote>the Application Developer is ensured that there are no additional concurrency issues beyond those encountered in a non-distributed container.</blockquote>
 * However, many servlet containers require a redundant call to {@link jakarta.servlet.http.HttpSession#setAttribute(String, Object)} after a mutable attribute value is modified.
 * @see ResetAtomicIntegerRequestIdentifierFilter
 * @author Paul Ferraro
 */
@WebFilter(urlPatterns = "/*")
public class AtomicIntegerRequestIdentifierFilter extends RequestIdentifierFilter {
	private static final long serialVersionUID = 2933347474895361068L;

	/** Default constructor */
	public AtomicIntegerRequestIdentifierFilter() {
		super(new RequestIdentifierManager.AtomicIntegerRequestIdentifierManager(false));
	}
}
