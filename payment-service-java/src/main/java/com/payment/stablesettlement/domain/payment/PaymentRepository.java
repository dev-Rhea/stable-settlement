package com.payment.stablesettlement.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    boolean existsByPaymentReference(String paymentReference);

    Optional<Payment> findByPgTransactionId(String pgTransactionId);

    List<Payment> findTop100ByStatusAndPgRequestedAtBeforeOrderByPgRequestedAtAsc(
            PaymentStatus status, LocalDateTime threshold);
}
