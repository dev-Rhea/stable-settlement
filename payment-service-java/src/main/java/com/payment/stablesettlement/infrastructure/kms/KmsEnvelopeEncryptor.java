package com.payment.stablesettlement.infrastructure.kms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@ConditionalOnBean(KmsClient.class)
public class KmsEnvelopeEncryptor {

    private static final Logger log = LoggerFactory.getLogger(KmsEnvelopeEncryptor.class);
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final KmsClient kmsClient;

    public KmsEnvelopeEncryptor(KmsClient kmsClient) {
        this.kmsClient = kmsClient;
    }

    public record DataKeyPair(byte[] plaintextKey, String encryptedKeyBase64) {}

    public DataKeyPair generateDataKey(String kmsKeyId) {
        GenerateDataKeyResponse response = kmsClient.generateDataKey(
                GenerateDataKeyRequest.builder()
                        .keyId(kmsKeyId)
                        .keySpec(DataKeySpec.AES_256)
                        .build()
        );

        byte[] plaintextKey = response.plaintext().asByteArray();
        String encryptedKeyBase64 = Base64.getEncoder().encodeToString(
                response.ciphertextBlob().asByteArray()
        );

        return new DataKeyPair(plaintextKey, encryptedKeyBase64);
    }

    public String encrypt(byte[] plaintext, byte[] dataKey) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new KmsEncryptionException("Encryption failed", e);
        } finally {
            if (dataKey != null) {
                Arrays.fill(dataKey, (byte) 0);
            }
        }
    }

    public byte[] decrypt(String ciphertextBase64, String encryptedDataKeyBase64, String kmsKeyId) {
        byte[] plaintextKey = null;
        try {
            byte[] encryptedDataKey = Base64.getDecoder().decode(encryptedDataKeyBase64);

            plaintextKey = kmsClient.decrypt(
                    DecryptRequest.builder()
                            .ciphertextBlob(SdkBytes.fromByteArray(encryptedDataKey))
                            .keyId(kmsKeyId)
                            .build()
            ).plaintext().asByteArray();

            byte[] combined = Base64.getDecoder().decode(ciphertextBase64);
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(plaintextKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return cipher.doFinal(ciphertext);
        } catch (KmsEncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsEncryptionException("Decryption failed", e);
        } finally {
            if (plaintextKey != null) {
                Arrays.fill(plaintextKey, (byte) 0);
            }
        }
    }

    public static class KmsEncryptionException extends RuntimeException {
        public KmsEncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
