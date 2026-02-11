from __future__ import annotations

import base64
import logging
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

logger = logging.getLogger(__name__)

GCM_NONCE_LENGTH = 12


class KmsEncryptor:

    def __init__(self, kms_key_id: str, region: str, endpoint_url: str | None = None):
        import boto3

        kwargs: dict = {"service_name": "kms", "region_name": region}
        if endpoint_url:
            kwargs["endpoint_url"] = endpoint_url
        self._client = boto3.client(**kwargs)
        self._kms_key_id = kms_key_id

    def encrypt(self, plaintext: bytes) -> tuple[str, str]:
        response = self._client.generate_data_key(
            KeyId=self._kms_key_id,
            KeySpec="AES_256",
        )
        plaintext_dek = bytearray(response["Plaintext"])
        encrypted_dek = response["CiphertextBlob"]

        try:
            nonce = os.urandom(GCM_NONCE_LENGTH)
            aesgcm = AESGCM(bytes(plaintext_dek))
            ciphertext = aesgcm.encrypt(nonce, plaintext, None)

            combined = nonce + ciphertext
            encrypted_data_b64 = base64.b64encode(combined).decode("utf-8")
            encrypted_dek_b64 = base64.b64encode(encrypted_dek).decode("utf-8")

            return encrypted_data_b64, encrypted_dek_b64
        finally:
            for i in range(len(plaintext_dek)):
                plaintext_dek[i] = 0
            del plaintext_dek

    def decrypt(self, encrypted_data_b64: str, encrypted_dek_b64: str) -> bytes:
        encrypted_dek = base64.b64decode(encrypted_dek_b64)

        response = self._client.decrypt(
            CiphertextBlob=encrypted_dek,
            KeyId=self._kms_key_id,
        )
        plaintext_dek = bytearray(response["Plaintext"])

        try:
            combined = base64.b64decode(encrypted_data_b64)
            nonce = combined[:GCM_NONCE_LENGTH]
            ciphertext = combined[GCM_NONCE_LENGTH:]

            aesgcm = AESGCM(bytes(plaintext_dek))
            return aesgcm.decrypt(nonce, ciphertext, None)
        finally:
            for i in range(len(plaintext_dek)):
                plaintext_dek[i] = 0
            del plaintext_dek
