package com.payment.stablesettlement.infrastructure.kms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KmsEnvelopeEncryptorTest {

    @Mock
    private KmsClient kmsClient;

    private KmsEnvelopeEncryptor encryptor;

    private static final String KMS_KEY_ID = "alias/test-key";
    private static final byte[] TEST_DATA_KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            TEST_DATA_KEY[i] = (byte) i;
        }
    }

    private static final byte[] ENCRYPTED_DATA_KEY = "encrypted-key-bytes".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        encryptor = new KmsEnvelopeEncryptor(kmsClient);
    }

    @Test
    @DisplayName("generateDataKey - KMS GenerateDataKey 호출 검증")
    void generateDataKeyCallsKms() {
        when(kmsClient.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenReturn(GenerateDataKeyResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(TEST_DATA_KEY))
                        .ciphertextBlob(SdkBytes.fromByteArray(ENCRYPTED_DATA_KEY))
                        .build());

        KmsEnvelopeEncryptor.DataKeyPair pair = encryptor.generateDataKey(KMS_KEY_ID);

        assertThat(pair.plaintextKey()).hasSize(32);
        assertThat(pair.encryptedKeyBase64()).isNotBlank();
        verify(kmsClient).generateDataKey(any(GenerateDataKeyRequest.class));
    }

    @Test
    @DisplayName("encrypt - AES-GCM Base64 출력 형식 검증")
    void encryptProducesBase64Output() {
        byte[] plaintext = "test-plaintext".getBytes(StandardCharsets.UTF_8);

        String encrypted = encryptor.encrypt(plaintext, TEST_DATA_KEY.clone());

        assertThat(encrypted).isNotBlank();
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        assertThat(decoded.length).isGreaterThan(12 + plaintext.length);
    }

    @Test
    @DisplayName("encrypt → decrypt 라운드트립 성공")
    void encryptDecryptRoundTrip() {
        byte[] plaintext = "Hello KMS World!".getBytes(StandardCharsets.UTF_8);

        when(kmsClient.decrypt(any(DecryptRequest.class)))
                .thenReturn(DecryptResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(TEST_DATA_KEY.clone()))
                        .build());

        String encryptedKeyBase64 = Base64.getEncoder().encodeToString(ENCRYPTED_DATA_KEY);
        String encrypted = encryptor.encrypt(plaintext, TEST_DATA_KEY.clone());

        byte[] decrypted = encryptor.decrypt(encrypted, encryptedKeyBase64, KMS_KEY_ID);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("잘못된 ciphertext → KmsEncryptionException")
    void decryptInvalidCiphertextThrows() {
        when(kmsClient.decrypt(any(DecryptRequest.class)))
                .thenReturn(DecryptResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(TEST_DATA_KEY.clone()))
                        .build());

        String invalidCiphertext = Base64.getEncoder().encodeToString(new byte[20]);
        String encryptedKeyBase64 = Base64.getEncoder().encodeToString(ENCRYPTED_DATA_KEY);

        assertThatThrownBy(() -> encryptor.decrypt(invalidCiphertext, encryptedKeyBase64, KMS_KEY_ID))
                .isInstanceOf(KmsEnvelopeEncryptor.KmsEncryptionException.class);
    }

    @Test
    @DisplayName("빈 plaintext encrypt → decrypt 라운드트립")
    void encryptDecryptEmptyData() {
        byte[] plaintext = new byte[0];

        when(kmsClient.decrypt(any(DecryptRequest.class)))
                .thenReturn(DecryptResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(TEST_DATA_KEY.clone()))
                        .build());

        String encryptedKeyBase64 = Base64.getEncoder().encodeToString(ENCRYPTED_DATA_KEY);
        String encrypted = encryptor.encrypt(plaintext, TEST_DATA_KEY.clone());

        byte[] decrypted = encryptor.decrypt(encrypted, encryptedKeyBase64, KMS_KEY_ID);

        assertThat(decrypted).isEqualTo(plaintext);
    }
}
