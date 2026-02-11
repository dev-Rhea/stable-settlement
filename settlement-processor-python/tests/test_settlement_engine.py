import datetime
from decimal import Decimal

import pytest

from src.models import MerchantFeePolicy, SettlementDetail
from src.settlement_engine import SettlementEngine


def _insert_policy(
    session,
    merchant_id=100,
    fee_type="PERCENTAGE",
    fee_value=Decimal("0.0350"),
    currency="KRW",
    min_fee=None,
    max_fee=None,
    effective_from=None,
    effective_to=None,
):
    if effective_from is None:
        effective_from = datetime.date(2020, 1, 1)
    policy = MerchantFeePolicy(
        merchant_id=merchant_id,
        fee_type=fee_type,
        fee_value=fee_value,
        currency=currency,
        min_fee=min_fee,
        max_fee=max_fee,
        effective_from=effective_from,
        effective_to=effective_to,
    )
    session.add(policy)
    session.commit()
    return policy


def _make_event(
    payment_reference="PAY-001",
    receiver_id=100,
    amount="10000",
    currency="KRW",
    status="COMPLETED",
):
    return {
        "paymentReference": payment_reference,
        "senderId": "1",
        "receiverId": str(receiver_id),
        "amount": amount,
        "currency": currency,
        "status": status,
    }


class TestSettlementEnginePercentage:
    def test_percentage_fee_calculation(self, session_factory):
        with session_factory() as s:
            _insert_policy(s, fee_value=Decimal("0.0350"))

        engine = SettlementEngine(session_factory)
        detail = engine.process_payment(_make_event(amount="10000"))

        assert detail is not None
        assert detail.fee_amount == Decimal("350.00")
        assert detail.net_amount == Decimal("9650.00")
        assert detail.fee_type == "PERCENTAGE"
        assert detail.fee_rate == Decimal("0.0350")

    def test_min_fee_applied(self, session_factory):
        with session_factory() as s:
            _insert_policy(s, fee_value=Decimal("0.0100"), min_fee=Decimal("500.00"))

        engine = SettlementEngine(session_factory)
        # 1% of 10000 = 100, but min_fee = 500
        detail = engine.process_payment(_make_event(amount="10000"))

        assert detail is not None
        assert detail.fee_amount == Decimal("500.00")
        assert detail.net_amount == Decimal("9500.00")

    def test_max_fee_applied(self, session_factory):
        with session_factory() as s:
            _insert_policy(s, fee_value=Decimal("0.1000"), max_fee=Decimal("500.00"))

        engine = SettlementEngine(session_factory)
        # 10% of 10000 = 1000, but max_fee = 500
        detail = engine.process_payment(_make_event(amount="10000"))

        assert detail is not None
        assert detail.fee_amount == Decimal("500.00")
        assert detail.net_amount == Decimal("9500.00")


class TestSettlementEngineFixed:
    def test_fixed_fee_calculation(self, session_factory):
        with session_factory() as s:
            _insert_policy(s, fee_type="FIXED", fee_value=Decimal("1000.0000"))

        engine = SettlementEngine(session_factory)
        detail = engine.process_payment(_make_event(amount="50000"))

        assert detail is not None
        assert detail.fee_amount == Decimal("1000.00")
        assert detail.net_amount == Decimal("49000.00")
        assert detail.fee_type == "FIXED"


class TestSettlementEngineEdgeCases:
    def test_no_fee_policy_raises_value_error(self, session_factory):
        engine = SettlementEngine(session_factory)
        with pytest.raises(ValueError, match="No fee policy found"):
            engine.process_payment(_make_event())

    def test_duplicate_payment_reference_returns_none(self, session_factory):
        with session_factory() as s:
            _insert_policy(s)

        engine = SettlementEngine(session_factory)
        first = engine.process_payment(_make_event(payment_reference="PAY-DUP"))
        assert first is not None

        second = engine.process_payment(_make_event(payment_reference="PAY-DUP"))
        assert second is None

    def test_zero_amount(self, session_factory):
        with session_factory() as s:
            _insert_policy(s, fee_value=Decimal("0.0350"))

        engine = SettlementEngine(session_factory)
        detail = engine.process_payment(_make_event(amount="0"))

        assert detail is not None
        assert detail.fee_amount == Decimal("0.00")
        assert detail.net_amount == Decimal("0.00")

    def test_large_amount_precision(self, session_factory):
        with session_factory() as s:
            _insert_policy(s, fee_value=Decimal("0.0350"))

        engine = SettlementEngine(session_factory)
        detail = engine.process_payment(_make_event(amount="99999999999.99"))

        assert detail is not None
        assert detail.fee_amount == Decimal("3500000000.00")
        assert detail.net_amount == Decimal("96499999999.99")

    def test_multi_currency_policy(self, session_factory):
        with session_factory() as s:
            _insert_policy(s, currency="KRW", fee_value=Decimal("0.0350"))
            _insert_policy(
                s,
                currency="USD",
                fee_value=Decimal("0.0250"),
                effective_from=datetime.date(2020, 1, 2),
            )

        engine = SettlementEngine(session_factory)
        detail_krw = engine.process_payment(
            _make_event(payment_reference="PAY-KRW", currency="KRW", amount="10000")
        )
        detail_usd = engine.process_payment(
            _make_event(payment_reference="PAY-USD", currency="USD", amount="10000")
        )

        assert detail_krw.fee_amount == Decimal("350.00")
        assert detail_usd.fee_amount == Decimal("250.00")

    def test_expired_policy_not_used(self, session_factory):
        with session_factory() as s:
            _insert_policy(
                s,
                effective_from=datetime.date(2020, 1, 1),
                effective_to=datetime.date(2020, 12, 31),
            )

        engine = SettlementEngine(session_factory)
        with pytest.raises(ValueError, match="No fee policy found"):
            engine.process_payment(_make_event())

    def test_net_amount_equals_gross_minus_fee(self, session_factory):
        with session_factory() as s:
            _insert_policy(s, fee_value=Decimal("0.0333"))

        engine = SettlementEngine(session_factory)
        detail = engine.process_payment(_make_event(amount="12345.67"))

        expected_fee = (Decimal("12345.67") * Decimal("0.0333")).quantize(
            Decimal("0.01")
        )
        assert detail.fee_amount == expected_fee
        assert detail.net_amount == Decimal("12345.67") - expected_fee
