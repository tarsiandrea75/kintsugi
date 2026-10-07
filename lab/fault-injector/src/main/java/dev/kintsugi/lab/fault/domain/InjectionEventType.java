package dev.kintsugi.lab.fault.domain;

/** The steps of an injection's life recorded in {@code injection_events}. */
public enum InjectionEventType {

	ACTIVATION_STARTED, ACTIVATED, ACTIVATION_FAILED, RESTORED, RESTORE_FAILED

}
