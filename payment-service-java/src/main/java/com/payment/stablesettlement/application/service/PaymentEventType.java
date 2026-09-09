package com.payment.stablesettlement.application.service;

/**
 * Outbox 로 발행되는 결제 이벤트 종류. 구독자가 상태 필드를 파싱하지 않고도
 * 관심 있는 사건만 골라낼 수 있도록 세분화한다.
 */
public enum PaymentEventType {
    PAYMENT_PENDING,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    PAYMENT_TIMEOUT
}
