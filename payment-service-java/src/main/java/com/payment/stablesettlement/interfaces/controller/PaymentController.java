package com.payment.stablesettlement.interfaces.controller;

import com.payment.stablesettlement.application.service.PaymentService;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.interfaces.dto.ApiResponse;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import com.payment.stablesettlement.interfaces.dto.PaymentResponse;
import jakarta.validation.Valid;
import lombok.Generated;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.protocol.types.Field.Str;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> processPayment(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody PaymentRequest request) {
        if (!request.senderId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Cannot process payment for another user"));
        }

        Payment payment = paymentService.processPayment(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(PaymentResponse.from(payment)));
    }

    @GetMapping("/{paymentReference}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment (
        @AuthenticationPrincipal Long userId,
        @PathVariable String paymentReference
    ) {
        Payment payment = paymentService.getPayment(paymentReference);

        if(!payment.getSenderId().equals(userId) && !payment.getReceiverId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Cannot access another user's payment"));
        }
        return ResponseEntity.ok(ApiResponse.success(PaymentResponse.from(payment)));
    }
}
