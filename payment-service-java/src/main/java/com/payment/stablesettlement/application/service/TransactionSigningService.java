package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.signing.SigningKey;
import com.payment.stablesettlement.domain.signing.SigningKeyRepository;
import com.payment.stablesettlement.infrastructure.kms.KmsEnvelopeEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.kms.KmsClient;

import org.springframework.dao.DataIntegrityViolationException;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
@Service
@ConditionalOnBean(KmsClient.class)
public class TransactionSigningService implements TransactionSigner {

    private static final String ALGORITHM = "SHA256withECDSA";
    private static final String EC_CURVE = "secp256r1";

    private final SigningKeyRepository signingKeyRepository;
    private final KmsEnvelopeEncryptor kmsEnvelopeEncryptor;
    private final Clock clock;
    private final String kmsKeyId;

    public TransactionSigningService(SigningKeyRepository signingKeyRepository,
                                     KmsEnvelopeEncryptor kmsEnvelopeEncryptor,
                                     Clock clock,
                                     @Value("${kms.key-id}") String kmsKeyId) {
        this.signingKeyRepository = signingKeyRepository;
        this.kmsEnvelopeEncryptor = kmsEnvelopeEncryptor;
        this.clock = clock;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public String signTransaction(Payment payment) {
        byte[] plaintextKeyBytes = null;
        try {
            SigningKey signingKey = signingKeyRepository.findByUserId(payment.getSenderId())
                    .orElseGet(() -> generateAndStoreKey(payment.getSenderId()));

            plaintextKeyBytes = kmsEnvelopeEncryptor.decrypt(
                    signingKey.getEncryptedPrivateKey(),
                    signingKey.getEncryptedDataKey(),
                    signingKey.getKmsKeyId()
            );

            PrivateKey privateKey = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(plaintextKeyBytes));

            String canonicalData = buildCanonicalData(payment);

            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(canonicalData.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Transaction signing failed for payment: {}", payment.getPaymentReference(), e);
            throw new PaymentException("SIGNING_FAILED", "Transaction signing failed");
        } finally {
            if (plaintextKeyBytes != null) {
                Arrays.fill(plaintextKeyBytes, (byte) 0);
            }
        }
    }

    private SigningKey generateAndStoreKey(Long userId) {
        byte[] plaintextDataKey = null;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
            keyPairGenerator.initialize(new ECGenParameterSpec(EC_CURVE));
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            KmsEnvelopeEncryptor.DataKeyPair dataKeyPair = kmsEnvelopeEncryptor.generateDataKey(kmsKeyId);
            plaintextDataKey = dataKeyPair.plaintextKey();

            String encryptedPrivateKey = kmsEnvelopeEncryptor.encrypt(
                    keyPair.getPrivate().getEncoded(), plaintextDataKey
            );
            String publicKeyBase64 = Base64.getEncoder().encodeToString(
                    keyPair.getPublic().getEncoded()
            );

            SigningKey signingKey = new SigningKey(
                    userId,
                    encryptedPrivateKey,
                    dataKeyPair.encryptedKeyBase64(),
                    kmsKeyId,
                    publicKeyBase64,
                    ALGORITHM,
                    clock
            );

            signingKey = signingKeyRepository.save(signingKey);
            log.info("Generated new signing key for userId={}", userId);

            return signingKey;
        } catch (DataIntegrityViolationException e) {
            log.info("Signing key already created concurrently for userId={}", userId);
            return signingKeyRepository.findByUserId(userId)
                    .orElseThrow(() -> new PaymentException("SIGNING_FAILED",
                            "Failed to retrieve signing key after concurrent creation"));
        } catch (Exception e) {
            log.error("Failed to generate signing key for userId={}", userId, e);
            throw new PaymentException("SIGNING_FAILED", "Failed to generate signing key");
        } finally {
            if (plaintextDataKey != null) {
                Arrays.fill(plaintextDataKey, (byte) 0);
            }
        }
    }

    String buildCanonicalData(Payment payment) {
        return String.join("|",
                payment.getPaymentReference(),
                String.valueOf(payment.getSenderId()),
                String.valueOf(payment.getReceiverId()),
                payment.getAmount().toPlainString(),
                payment.getCurrency()
        );
    }
}
