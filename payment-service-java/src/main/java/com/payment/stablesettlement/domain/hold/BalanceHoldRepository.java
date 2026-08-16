package com.payment.stablesettlement.domain.hold;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceHoldRepository extends JpaRepository<BalanceHold, Long> {

    Optional<BalanceHold> findByPaymentReference(String paymentReference);
}
