package dev.kintsugi.lab.fault.application;

import java.util.UUID;

/** No injection has this id (404). */
public class InjectionNotFoundException extends RuntimeException {

	public InjectionNotFoundException(UUID id) {
		super("injection not found: " + id);
	}

}
