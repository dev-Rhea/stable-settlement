from __future__ import annotations

import json
import logging
import signal
from typing import TYPE_CHECKING

from config.settings import Settings
from src.data_cleanser import DataCleanser
from src.settlement_engine import SettlementEngine

if TYPE_CHECKING:
    from src.merchant_data_service import MerchantDataService

logger = logging.getLogger(__name__)


class PaymentEventConsumer:
    def __init__(
        self,
        settings: Settings,
        settlement_engine: SettlementEngine,
        data_cleanser: DataCleanser,
        merchant_data_service: MerchantDataService | None = None,
    ):
        self._settings = settings
        self._settlement_engine = settlement_engine
        self._data_cleanser = data_cleanser
        self._merchant_data_service = merchant_data_service
        self._running = False

    def start(self) -> None:
        from confluent_kafka import Consumer

        consumer = Consumer(
            {
                "bootstrap.servers": self._settings.kafka_bootstrap_servers,
                "group.id": self._settings.kafka_group_id,
                "auto.offset.reset": "earliest",
                "enable.auto.commit": False,
            }
        )
        consumer.subscribe([self._settings.kafka_topic])

        self._running = True

        def _shutdown(signum, frame):
            logger.info("Received signal %s, shutting down...", signum)
            self._running = False

        signal.signal(signal.SIGTERM, _shutdown)
        signal.signal(signal.SIGINT, _shutdown)

        logger.info(
            "Consumer started, topic=%s, group=%s",
            self._settings.kafka_topic,
            self._settings.kafka_group_id,
        )

        try:
            while self._running:
                msg = consumer.poll(1.0)
                if msg is None:
                    continue
                if msg.error():
                    logger.error("Kafka error: %s", msg.error())
                    continue

                self._handle_message(consumer, msg)
        finally:
            consumer.close()
            logger.info("Consumer closed")

    def _handle_message(self, consumer, msg) -> None:
        try:
            payload = json.loads(msg.value().decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            logger.error("Failed to parse message: %s", e)
            consumer.commit(message=msg)
            return

        if not payload:
            logger.warning("Empty payload, skipping")
            consumer.commit(message=msg)
            return

        status = payload.get("status")
        if status != "COMPLETED":
            logger.debug("Skipping non-COMPLETED event: status=%s", status)
            consumer.commit(message=msg)
            return

        try:
            detail = self._settlement_engine.process_payment(payload)
            if detail is not None:
                self._data_cleanser.upsert_summary(detail)
                self._encrypt_merchant_data(detail)
            consumer.commit(message=msg)
        except ValueError as e:
            logger.error(
                "Non-retryable error for ref=%s: %s",
                payload.get("paymentReference", "unknown"),
                e,
            )
            consumer.commit(message=msg)
        except Exception:
            logger.exception(
                "Retryable error for ref=%s, not committing offset",
                payload.get("paymentReference", "unknown"),
            )

    def _encrypt_merchant_data(self, detail) -> None:
        if self._merchant_data_service is None:
            return
        try:
            self._merchant_data_service.store_sensitive_data(
                merchant_id=detail.merchant_id,
                account_info={
                    "merchant_id": detail.merchant_id,
                    "currency": detail.currency,
                    "last_settlement_ref": detail.payment_reference,
                },
            )
        except Exception:
            logger.exception(
                "Failed to encrypt merchant data for merchant_id=%s (non-blocking)",
                detail.merchant_id,
            )
