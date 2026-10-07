package dev.kintsugi.lab.inventory.domain;

/** Outcome of a reservation: every line was reserved, or none was because at least one line was short. */
public enum ReservationStatus {

	RESERVED, INSUFFICIENT_STOCK

}
