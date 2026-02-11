from __future__ import annotations

import logging

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from config.settings import Settings
from src.consumer import PaymentEventConsumer
from src.data_cleanser import DataCleanser
from src.settlement_engine import SettlementEngine

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger(__name__)


def main() -> None:
    settings = Settings.from_env()
    logger.info("Starting settlement processor...")
    logger.info("DB: %s", settings.db_url.split("@")[-1] if "@" in settings.db_url else "***")
    logger.info("Kafka: %s", settings.kafka_bootstrap_servers)

    engine = create_engine(settings.db_url, pool_pre_ping=True)
    session_factory = sessionmaker(bind=engine)

    settlement_engine = SettlementEngine(session_factory)
    data_cleanser = DataCleanser(session_factory)

    merchant_data_service = None
    if settings.kms_enabled:
        from src.kms_encryptor import KmsEncryptor
        from src.merchant_data_service import MerchantDataService

        kms_encryptor = KmsEncryptor(
            kms_key_id=settings.aws_kms_key_id,
            region=settings.aws_region,
            endpoint_url=settings.aws_endpoint_url,
        )
        merchant_data_service = MerchantDataService(
            session_factory=session_factory,
            kms_encryptor=kms_encryptor,
            kms_key_id=settings.aws_kms_key_id,
        )
        logger.info("KMS encryption enabled (region=%s)", settings.aws_region)
    else:
        logger.info("KMS encryption disabled")

    consumer = PaymentEventConsumer(
        settings, settlement_engine, data_cleanser, merchant_data_service
    )
    consumer.start()


if __name__ == "__main__":
    main()
