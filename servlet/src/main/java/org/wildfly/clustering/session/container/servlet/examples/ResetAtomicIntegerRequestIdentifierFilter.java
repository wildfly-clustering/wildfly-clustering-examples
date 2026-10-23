/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import jakarta.servlet.annotation.WebFilter;

/**
 * A servlet filter that provides a unique identifier to requests for a given session via a mutable AtomicInteger session attribute.
 * This implementation is safe for use by concurrent requests for the same session, on any spec-compliant servlet container.
 * @author Paul Ferraro
 */
@WebFilter(urlPatterns = "/*")
public class ResetAtomicIntegerRequestIdentifierFilter extends RequestIdentifierFilter {
	private static final long serialVersionUID = 2933347474895361068L;

	/** Default constructor */
	public ResetAtomicIntegerRequestIdentifierFilter() {
		super(new RequestIdentifierManager.AtomicIntegerRequestIdentifierManager(true));
	}
}
