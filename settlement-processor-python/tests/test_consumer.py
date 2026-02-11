import json
from unittest.mock import MagicMock, patch

import pytest

from config.settings import Settings
from src.consumer import PaymentEventConsumer
from src.models import SettlementDetail


def _make_settings():
    return Settings(
        db_url="sqlite:///:memory:",
        kafka_bootstrap_servers="localhost:9092",
        kafka_group_id="test-group",
        kafka_topic="payment-events",
        kms_enabled=False,
        aws_region="ap-northeast-2",
        aws_kms_key_id="alias/test-key",
        aws_endpoint_url=None,
    )


def _make_message(payload: dict | None = None, error=None):
    msg = MagicMock()
    msg.error.return_value = error
    if payload is not None:
        msg.value.return_value = json.dumps(payload).encode("utf-8")
    else:
        msg.value.return_value = b""
    return msg


def _make_completed_event():
    return {
        "paymentReference": "PAY-001",
        "senderId": "1",
        "receiverId": "100",
        "amount": "10000",
        "currency": "KRW",
        "status": "COMPLETED",
    }


class TestPaymentEventConsumer:
    def test_completed_event_calls_engine_and_cleanser(self):
        settings = _make_settings()
        engine = MagicMock()
        cleanser = MagicMock()
        detail = MagicMock(spec=SettlementDetail)
        engine.process_payment.return_value = detail

        consumer = PaymentEventConsumer(settings, engine, cleanser)
        msg = _make_message(_make_completed_event())
        mock_kafka_consumer = MagicMock()

        consumer._handle_message(mock_kafka_consumer, msg)

        engine.process_payment.assert_called_once()
        cleanser.upsert_summary.assert_called_once_with(detail)
        mock_kafka_consumer.commit.assert_called_once_with(message=msg)

    def test_json_parse_failure_skips_and_commits(self):
        settings = _make_settings()
        engine = MagicMock()
        cleanser = MagicMock()

        consumer = PaymentEventConsumer(settings, engine, cleanser)
        msg = MagicMock()
        msg.value.return_value = b"not-json{{"
        mock_kafka_consumer = MagicMock()

        consumer._handle_message(mock_kafka_consumer, msg)

        engine.process_payment.assert_not_called()
        mock_kafka_consumer.commit.assert_called_once_with(message=msg)

    def test_non_completed_status_skips_and_commits(self):
        settings = _make_settings()
        engine = MagicMock()
        cleanser = MagicMock()

        consumer = PaymentEventConsumer(settings, engine, cleanser)
        event = _make_completed_event()
        event["status"] = "PENDING"
        msg = _make_message(event)
        mock_kafka_consumer = MagicMock()

        consumer._handle_message(mock_kafka_consumer, msg)

        engine.process_payment.assert_not_called()
        mock_kafka_consumer.commit.assert_called_once_with(message=msg)

    def test_value_error_commits_offset(self):
        settings = _make_settings()
        engine = MagicMock()
        engine.process_payment.side_effect = ValueError("No fee policy")
        cleanser = MagicMock()

        consumer = PaymentEventConsumer(settings, engine, cleanser)
        msg = _make_message(_make_completed_event())
        mock_kafka_consumer = MagicMock()

        consumer._handle_message(mock_kafka_consumer, msg)

        cleanser.upsert_summary.assert_not_called()
        mock_kafka_consumer.commit.assert_called_once_with(message=msg)

    def test_retryable_error_does_not_commit(self):
        settings = _make_settings()
        engine = MagicMock()
        engine.process_payment.side_effect = RuntimeError("DB connection lost")
        cleanser = MagicMock()

        consumer = PaymentEventConsumer(settings, engine, cleanser)
        msg = _make_message(_make_completed_event())
        mock_kafka_consumer = MagicMock()

        consumer._handle_message(mock_kafka_consumer, msg)

        cleanser.upsert_summary.assert_not_called()
        mock_kafka_consumer.commit.assert_not_called()

    def test_poll_timeout_returns_none_continues(self):
        settings = _make_settings()
        engine = MagicMock()
        cleanser = MagicMock()

        consumer = PaymentEventConsumer(settings, engine, cleanser)

        call_count = 0

        def poll_side_effect(timeout):
            nonlocal call_count
            call_count += 1
            if call_count >= 2:
                consumer._running = False
            return None

        with patch("confluent_kafka.Consumer") as MockConsumer:
            mock_kafka = MockConsumer.return_value
            mock_kafka.poll.side_effect = poll_side_effect

            consumer.start()

            engine.process_payment.assert_not_called()
            mock_kafka.close.assert_called_once()

    def test_graceful_shutdown(self):
        settings = _make_settings()
        engine = MagicMock()
        cleanser = MagicMock()

        consumer = PaymentEventConsumer(settings, engine, cleanser)

        def poll_side_effect(timeout):
            consumer._running = False
            return None

        with patch("confluent_kafka.Consumer") as MockConsumer:
            mock_kafka = MockConsumer.return_value
            mock_kafka.poll.side_effect = poll_side_effect

            consumer.start()

            mock_kafka.close.assert_called_once()

    def test_completed_event_calls_merchant_data_service(self):
        settings = _make_settings()
        engine = MagicMock()
        cleanser = MagicMock()
        merchant_svc = MagicMock()
        detail = MagicMock(spec=SettlementDetail)
        detail.merchant_id = 100
        detail.currency = "KRW"
        detail.payment_reference = "PAY-001"
        engine.process_payment.return_value = detail

        consumer = PaymentEventConsumer(
            settings, engine, cleanser, merchant_data_service=merchant_svc
        )
        msg = _make_message(_make_completed_event())
        mock_kafka_consumer = MagicMock()

        consumer._handle_message(mock_kafka_consumer, msg)

        merchant_svc.store_sensitive_data.assert_called_once_with(
            merchant_id=100,
            account_info={
                "merchant_id": 100,
                "currency": "KRW",
                "last_settlement_ref": "PAY-001",
            },
        )

    def test_merchant_data_service_failure_non_blocking(self):
        settings = _make_settings()
        engine = MagicMock()
        cleanser = MagicMock()
        merchant_svc = MagicMock()
        merchant_svc.store_sensitive_data.side_effect = RuntimeError("KMS down")
        detail = MagicMock(spec=SettlementDetail)
        engine.process_payment.return_value = detail

        consumer = PaymentEventConsumer(
            settings, engine, cleanser, merchant_data_service=merchant_svc
        )
        msg = _make_message(_make_completed_event())
        mock_kafka_consumer = MagicMock()

        consumer._handle_message(mock_kafka_consumer, msg)

        cleanser.upsert_summary.assert_called_once_with(detail)
        mock_kafka_consumer.commit.assert_called_once_with(message=msg)

    def test_empty_payload_skips_and_commits(self):
        settings = _make_settings()
        engine = MagicMock()
        cleanser = MagicMock()

        consumer = PaymentEventConsumer(settings, engine, cleanser)
        msg = MagicMock()
        msg.value.return_value = b"{}"
        mock_kafka_consumer = MagicMock()

        consumer._handle_message(mock_kafka_consumer, msg)

        engine.process_payment.assert_not_called()
        mock_kafka_consumer.commit.assert_called_once_with(message=msg)
