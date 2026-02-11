from __future__ import annotations

import base64
import os
from unittest.mock import MagicMock, patch

import pytest

from src.kms_encryptor import GCM_NONCE_LENGTH


@pytest.fixture
def mock_kms_client():
    real_data_key = os.urandom(32)
    encrypted_dek = b"fake-encrypted-dek"

    mock_client = MagicMock()
    mock_client.generate_data_key.return_value = {
        "Plaintext": real_data_key,
        "CiphertextBlob": encrypted_dek,
    }
    mock_client.decrypt.return_value = {
        "Plaintext": real_data_key,
    }

    return mock_client, real_data_key, encrypted_dek


def _make_encryptor(mock_client):
    with patch("boto3.client", return_value=mock_client):
        from src.kms_encryptor import KmsEncryptor
        return KmsEncryptor(
            kms_key_id="alias/test-key",
            region="ap-northeast-2",
            endpoint_url="http://localhost:4566",
        )


class TestKmsEncryptor:

    def test_encrypt_decrypt_roundtrip(self, mock_kms_client):
        mock_client, real_data_key, encrypted_dek = mock_kms_client
        encryptor = _make_encryptor(mock_client)

        plaintext = b"Hello, World! Secret data here."
        encrypted_data_b64, encrypted_dek_b64 = encryptor.encrypt(plaintext)

        decrypted = encryptor.decrypt(encrypted_data_b64, encrypted_dek_b64)

        assert decrypted == plaintext

    def test_encrypt_output_is_base64(self, mock_kms_client):
        mock_client, _, _ = mock_kms_client
        encryptor = _make_encryptor(mock_client)

        encrypted_data_b64, encrypted_dek_b64 = encryptor.encrypt(b"test-data")

        decoded_data = base64.b64decode(encrypted_data_b64)
        assert len(decoded_data) > GCM_NONCE_LENGTH

        decoded_dek = base64.b64decode(encrypted_dek_b64)
        assert len(decoded_dek) > 0

    def test_generate_data_key_kms_call(self, mock_kms_client):
        mock_client, _, _ = mock_kms_client
        encryptor = _make_encryptor(mock_client)

        encryptor.encrypt(b"data")

        mock_client.generate_data_key.assert_called_once_with(
            KeyId="alias/test-key",
            KeySpec="AES_256",
        )

    def test_decrypt_calls_kms_decrypt(self, mock_kms_client):
        mock_client, _, _ = mock_kms_client
        encryptor = _make_encryptor(mock_client)

        encrypted_data_b64, encrypted_dek_b64 = encryptor.encrypt(b"test-data")
        encryptor.decrypt(encrypted_data_b64, encrypted_dek_b64)

        mock_client.decrypt.assert_called_once()
        call_kwargs = mock_client.decrypt.call_args[1]
        assert call_kwargs["KeyId"] == "alias/test-key"

    def test_decrypt_invalid_data_raises(self, mock_kms_client):
        mock_client, _, _ = mock_kms_client
        encryptor = _make_encryptor(mock_client)

        invalid_data = base64.b64encode(b"too-short").decode()
        valid_dek = base64.b64encode(b"fake-dek").decode()

        with pytest.raises(Exception):
            encryptor.decrypt(invalid_data, valid_dek)
