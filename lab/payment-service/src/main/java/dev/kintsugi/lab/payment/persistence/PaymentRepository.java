package dev.kintsugi.lab.payment.persistence;

import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

import dev.kintsugi.lab.payment.domain.Payment;

/** Stores and loads {@link Payment}s. */
public interface PaymentRepository extends ListCrudRepository<Payment, UUID> {

}
