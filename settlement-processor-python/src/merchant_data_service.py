from __future__ import annotations

import datetime
import json
import logging
from typing import Any

from sqlalchemy.orm import Session, sessionmaker

from src.kms_encryptor import KmsEncryptor
from src.models import MerchantSensitiveData

logger = logging.getLogger(__name__)


class MerchantDataService:

    def __init__(self, session_factory: sessionmaker, kms_encryptor: KmsEncryptor,
                 kms_key_id: str):
        self._session_factory = session_factory
        self._kms_encryptor = kms_encryptor
        self._kms_key_id = kms_key_id

    def store_sensitive_data(self, merchant_id: int,
                             account_info: dict[str, Any]) -> MerchantSensitiveData:
        plaintext = json.dumps(account_info).encode("utf-8")
        encrypted_data_b64, encrypted_dek_b64 = self._kms_encryptor.encrypt(plaintext)
        now = datetime.datetime.now(datetime.timezone.utc)

        with self._session_factory() as session:
            existing = session.query(MerchantSensitiveData).filter(
                MerchantSensitiveData.merchant_id == merchant_id
            ).first()

            if existing:
                existing.encrypted_account_info = encrypted_data_b64
                existing.encrypted_data_key = encrypted_dek_b64
                existing.kms_key_id = self._kms_key_id
                existing.updated_at = now
                session.commit()
                session.refresh(existing)
                session.expunge(existing)
                return existing

            record = MerchantSensitiveData(
                merchant_id=merchant_id,
                encrypted_account_info=encrypted_data_b64,
                encrypted_data_key=encrypted_dek_b64,
                kms_key_id=self._kms_key_id,
                created_at=now,
                updated_at=now,
            )
            session.add(record)
            session.commit()
            session.refresh(record)
            session.expunge(record)
            return record

    def retrieve_sensitive_data(self, merchant_id: int) -> dict[str, Any] | None:
        with self._session_factory() as session:
            record: MerchantSensitiveData | None = session.query(
                MerchantSensitiveData
            ).filter(
                MerchantSensitiveData.merchant_id == merchant_id
            ).first()

            if record is None:
                return None

            decrypted = bytearray(self._kms_encryptor.decrypt(
                record.encrypted_account_info,
                record.encrypted_data_key,
            ))
            try:
                return json.loads(decrypted.decode("utf-8"))
            finally:
                for i in range(len(decrypted)):
                    decrypted[i] = 0
