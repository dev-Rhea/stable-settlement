package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.signing.SigningKey;
import com.payment.stablesettlement.domain.signing.SigningKeyRepository;
import com.payment.stablesettlement.infrastructure.kms.KmsEnvelopeEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionSigningServiceTest {

    @Mock
    private SigningKeyRepository signingKeyRepository;

    @Mock
    private KmsEnvelopeEncryptor kmsEnvelopeEncryptor;

    private final Clock clock = Clock.fixed(
            Instant.parse("2025-01-15T10:00:00Z"), ZoneId.of("UTC"));

    private static final String KMS_KEY_ID = "alias/test-key";

    private TransactionSigningService signingService;

    private KeyPair testKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        signingService = new TransactionSigningService(
                signingKeyRepository, kmsEnvelopeEncryptor, clock, KMS_KEY_ID);

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        testKeyPair = keyPairGenerator.generateKeyPair();
    }

    @Test
    @DisplayName("기존 키로 서명 후 publicKey로 ECDSA verify 성공")
    void signWithExistingKeyVerifiable() throws Exception {
        byte[] privateKeyBytes = testKeyPair.getPrivate().getEncoded();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(
                testKeyPair.getPublic().getEncoded());

        SigningKey signingKey = new SigningKey(
                1L, "encrypted-pk", "encrypted-dek", KMS_KEY_ID,
                publicKeyBase64, "SHA256withECDSA", clock);

        when(signingKeyRepository.findByUserId(1L)).thenReturn(Optional.of(signingKey));
        when(kmsEnvelopeEncryptor.decrypt("encrypted-pk", "encrypted-dek", KMS_KEY_ID))
                .thenReturn(privateKeyBytes);

        Payment payment = Payment.builder()
                .paymentReference("PAY-001")
                .senderId(1L)
                .receiverId(2L)
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .clock(clock)
                .build();

        String signatureBase64 = signingService.signTransaction(payment);

        assertThat(signatureBase64).isNotBlank();

        // Verify with public key
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
        PublicKey publicKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(publicKeyBase64)));

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update("PAY-001|1|2|100.00|USDT".getBytes());
        assertThat(verifier.verify(signatureBytes)).isTrue();
    }

    @Test
    @DisplayName("키 없음 → 자동 생성 + 저장 검증")
    void signAutoGeneratesKeyWhenMissing() {
        when(signingKeyRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(kmsEnvelopeEncryptor.generateDataKey(KMS_KEY_ID))
                .thenReturn(new KmsEnvelopeEncryptor.DataKeyPair(
                        new byte[32], "encrypted-dek-base64"));
        when(kmsEnvelopeEncryptor.encrypt(any(byte[].class), any(byte[].class)))
                .thenReturn("encrypted-private-key");
        when(signingKeyRepository.save(any(SigningKey.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(kmsEnvelopeEncryptor.decrypt(anyString(), anyString(), eq(KMS_KEY_ID)))
                .thenAnswer(invocation -> {
                    // Return a fresh ECDSA private key for signing
                    try {
                        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
                        kpg.initialize(new ECGenParameterSpec("secp256r1"));
                        return kpg.generateKeyPair().getPrivate().getEncoded();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        Payment payment = Payment.builder()
                .paymentReference("PAY-002")
                .senderId(1L)
                .receiverId(2L)
                .amount(new BigDecimal("50.00"))
                .currency("USDT")
                .clock(clock)
                .build();

        String signature = signingService.signTransaction(payment);

        assertThat(signature).isNotBlank();

        ArgumentCaptor<SigningKey> captor = ArgumentCaptor.forClass(SigningKey.class);
        verify(signingKeyRepository).save(captor.capture());

        SigningKey savedKey = captor.getValue();
        assertThat(savedKey.getUserId()).isEqualTo(1L);
        assertThat(savedKey.getAlgorithm()).isEqualTo("SHA256withECDSA");
        assertThat(savedKey.getKmsKeyId()).isEqualTo(KMS_KEY_ID);
    }

    @Test
    @DisplayName("KMS 복호화 실패 → PaymentException(SIGNING_FAILED)")
    void signKmsFailureThrowsPaymentException() {
        SigningKey signingKey = new SigningKey(
                1L, "encrypted-pk", "encrypted-dek", KMS_KEY_ID,
                "public-key", "SHA256withECDSA", clock);

        when(signingKeyRepository.findByUserId(1L)).thenReturn(Optional.of(signingKey));
        when(kmsEnvelopeEncryptor.decrypt("encrypted-pk", "encrypted-dek", KMS_KEY_ID))
                .thenThrow(new KmsEnvelopeEncryptor.KmsEncryptionException(
                        "KMS failure", new RuntimeException()));

        Payment payment = Payment.builder()
                .paymentReference("PAY-003")
                .senderId(1L)
                .receiverId(2L)
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .clock(clock)
                .build();

        assertThatThrownBy(() -> signingService.signTransaction(payment))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo("SIGNING_FAILED");
    }

    @Test
    @DisplayName("정규화 데이터 형식: ref|sender|receiver|amount|currency")
    void buildCanonicalDataFormat() {
        Payment payment = Payment.builder()
                .paymentReference("PAY-001")
                .senderId(1L)
                .receiverId(2L)
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .clock(clock)
                .build();

        String canonical = signingService.buildCanonicalData(payment);

        assertThat(canonical).isEqualTo("PAY-001|1|2|100.00|USDT");
    }

    @Test
    @DisplayName("서명 값이 매번 다름 (비결정적 ECDSA)")
    void signatureIsNonDeterministic() throws Exception {
        byte[] privateKeyBytes = testKeyPair.getPrivate().getEncoded();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(
                testKeyPair.getPublic().getEncoded());

        SigningKey signingKey = new SigningKey(
                1L, "encrypted-pk", "encrypted-dek", KMS_KEY_ID,
                publicKeyBase64, "SHA256withECDSA", clock);

        when(signingKeyRepository.findByUserId(1L)).thenReturn(Optional.of(signingKey));
        when(kmsEnvelopeEncryptor.decrypt("encrypted-pk", "encrypted-dek", KMS_KEY_ID))
                .thenReturn(privateKeyBytes.clone())
                .thenReturn(privateKeyBytes.clone());

        Payment payment = Payment.builder()
                .paymentReference("PAY-001")
                .senderId(1L)
                .receiverId(2L)
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .clock(clock)
                .build();

        String sig1 = signingService.signTransaction(payment);
        String sig2 = signingService.signTransaction(payment);

        // ECDSA uses random k, so signatures differ
        assertThat(sig1).isNotEqualTo(sig2);
    }
}
