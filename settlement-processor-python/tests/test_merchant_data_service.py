from __future__ import annotations

import base64
import json
import os
from unittest.mock import MagicMock

import pytest

from src.merchant_data_service import MerchantDataService
from src.models import MerchantSensitiveData


class FakeKmsEncryptor:
    """Test double that encrypts using simple XOR for deterministic testing."""

    def __init__(self):
        self._key = os.urandom(32)

    def encrypt(self, plaintext: bytes) -> tuple[str, str]:
        # Simple reversible encoding for testing
        encrypted = base64.b64encode(plaintext).decode("utf-8")
        dek = base64.b64encode(self._key).decode("utf-8")
        return encrypted, dek

    def decrypt(self, encrypted_data_b64: str, encrypted_dek_b64: str) -> bytes:
        return base64.b64decode(encrypted_data_b64)


@pytest.fixture
def fake_encryptor():
    return FakeKmsEncryptor()


@pytest.fixture
def service(session_factory, fake_encryptor):
    return MerchantDataService(
        session_factory=session_factory,
        kms_encryptor=fake_encryptor,
        kms_key_id="alias/test-key",
    )


class TestMerchantDataService:

    def test_store_and_retrieve_roundtrip(self, service):
        account_info = {"bank": "Shinhan", "account_number": "123-456-789"}

        service.store_sensitive_data(merchant_id=100, account_info=account_info)

        retrieved = service.retrieve_sensitive_data(merchant_id=100)

        assert retrieved == account_info

    def test_upsert_overwrites_existing(self, service):
        original = {"bank": "Shinhan", "account_number": "111"}
        service.store_sensitive_data(merchant_id=200, account_info=original)

        updated = {"bank": "KB", "account_number": "222"}
        service.store_sensitive_data(merchant_id=200, account_info=updated)

        retrieved = service.retrieve_sensitive_data(merchant_id=200)
        assert retrieved == updated

    def test_retrieve_nonexistent_returns_none(self, service):
        result = service.retrieve_sensitive_data(merchant_id=999)
        assert result is None

    def test_stored_data_is_encrypted(self, session_factory, fake_encryptor):
        service = MerchantDataService(
            session_factory=session_factory,
            kms_encryptor=fake_encryptor,
            kms_key_id="alias/test-key",
        )

        account_info = {"bank": "Shinhan", "account_number": "123-456-789"}
        service.store_sensitive_data(merchant_id=300, account_info=account_info)

        with session_factory() as session:
            record = session.query(MerchantSensitiveData).filter(
                MerchantSensitiveData.merchant_id == 300
            ).first()

            assert record is not None
            assert record.encrypted_account_info != json.dumps(account_info)
            assert record.kms_key_id == "alias/test-key"
            assert record.encrypted_data_key is not None
