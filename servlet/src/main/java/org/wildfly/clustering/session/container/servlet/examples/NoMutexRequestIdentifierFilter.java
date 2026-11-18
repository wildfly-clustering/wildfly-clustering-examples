/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.Serial;

/**
 * A request identifier filter that performs reads/writes to the session without the use of a mutex.
 * This implementation is not safe for use by concurrent requests for the same session.
 * @author Paul Ferraro
 */
public class NoMutexRequestIdentifierFilter extends AbstractIntegerRequestIdentifierFilter {
	@Serial
	private static final long serialVersionUID = -9109103777029541239L;

	/** Default constructor */
	public NoMutexRequestIdentifierFilter() {
		// Do nothing
	}
}
