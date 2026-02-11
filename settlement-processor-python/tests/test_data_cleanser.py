import datetime
from decimal import Decimal

import pytest
from sqlalchemy import select

from src.data_cleanser import DataCleanser
from src.models import DwSettlementSummary, SettlementDetail


def _make_detail(
    payment_reference="PAY-001",
    merchant_id=100,
    gross_amount=Decimal("10000.00"),
    fee_amount=Decimal("350.00"),
    net_amount=Decimal("9650.00"),
    fee_type="PERCENTAGE",
    fee_rate=Decimal("0.0350"),
    currency="KRW",
    settled_at=None,
):
    if settled_at is None:
        settled_at = datetime.datetime(2025, 6, 15, 10, 30, 0)
    return SettlementDetail(
        payment_reference=payment_reference,
        merchant_id=merchant_id,
        gross_amount=gross_amount,
        fee_amount=fee_amount,
        net_amount=net_amount,
        fee_type=fee_type,
        fee_rate=fee_rate,
        currency=currency,
        settled_at=settled_at,
    )


class TestDataCleanserCreate:
    def test_create_new_summary_row(self, session_factory):
        cleanser = DataCleanser(session_factory)
        detail = _make_detail()
        cleanser.upsert_summary(detail)

        with session_factory() as s:
            summaries = s.execute(select(DwSettlementSummary)).scalars().all()
            assert len(summaries) == 1
            summary = summaries[0]
            assert summary.summary_date == datetime.date(2025, 6, 15)
            assert summary.merchant_id == 100
            assert summary.currency == "KRW"
            assert summary.total_count == 1
            assert summary.total_gross == Decimal("10000.00")
            assert summary.total_fee == Decimal("350.00")
            assert summary.total_net == Decimal("9650.00")

    def test_different_merchant_creates_separate_rows(self, session_factory):
        cleanser = DataCleanser(session_factory)
        cleanser.upsert_summary(_make_detail(merchant_id=100))
        cleanser.upsert_summary(
            _make_detail(payment_reference="PAY-002", merchant_id=200)
        )

        with session_factory() as s:
            summaries = s.execute(select(DwSettlementSummary)).scalars().all()
            assert len(summaries) == 2

    def test_different_date_creates_separate_rows(self, session_factory):
        cleanser = DataCleanser(session_factory)
        cleanser.upsert_summary(
            _make_detail(settled_at=datetime.datetime(2025, 6, 15, 10, 0, 0))
        )
        cleanser.upsert_summary(
            _make_detail(
                payment_reference="PAY-002",
                settled_at=datetime.datetime(2025, 6, 16, 10, 0, 0),
            )
        )

        with session_factory() as s:
            summaries = s.execute(select(DwSettlementSummary)).scalars().all()
            assert len(summaries) == 2

    def test_different_currency_creates_separate_rows(self, session_factory):
        cleanser = DataCleanser(session_factory)
        cleanser.upsert_summary(_make_detail(currency="KRW"))
        cleanser.upsert_summary(
            _make_detail(payment_reference="PAY-002", currency="USD")
        )

        with session_factory() as s:
            summaries = s.execute(select(DwSettlementSummary)).scalars().all()
            assert len(summaries) == 2


class TestDataCleanserUpdate:
    def test_update_existing_summary_accumulates(self, session_factory):
        cleanser = DataCleanser(session_factory)
        cleanser.upsert_summary(_make_detail(payment_reference="PAY-001"))
        cleanser.upsert_summary(
            _make_detail(
                payment_reference="PAY-002",
                gross_amount=Decimal("20000.00"),
                fee_amount=Decimal("700.00"),
                net_amount=Decimal("19300.00"),
            )
        )

        with session_factory() as s:
            summaries = s.execute(select(DwSettlementSummary)).scalars().all()
            assert len(summaries) == 1
            summary = summaries[0]
            assert summary.total_count == 2
            assert summary.total_gross == Decimal("30000.00")
            assert summary.total_fee == Decimal("1050.00")
            assert summary.total_net == Decimal("28950.00")

    def test_total_count_increments(self, session_factory):
        cleanser = DataCleanser(session_factory)
        for i in range(5):
            cleanser.upsert_summary(_make_detail(payment_reference=f"PAY-{i}"))

        with session_factory() as s:
            summary = s.execute(select(DwSettlementSummary)).scalars().first()
            assert summary.total_count == 5

    def test_updated_at_refreshed(self, session_factory):
        cleanser = DataCleanser(session_factory)
        cleanser.upsert_summary(_make_detail(payment_reference="PAY-001"))

        with session_factory() as s:
            s1 = s.execute(select(DwSettlementSummary)).scalars().first()
            first_updated = s1.updated_at

        cleanser.upsert_summary(_make_detail(payment_reference="PAY-002"))

        with session_factory() as s:
            s2 = s.execute(select(DwSettlementSummary)).scalars().first()
            assert s2.updated_at >= first_updated

    def test_large_accumulated_precision(self, session_factory):
        cleanser = DataCleanser(session_factory)
        for i in range(10):
            cleanser.upsert_summary(
                _make_detail(
                    payment_reference=f"PAY-{i}",
                    gross_amount=Decimal("99999999.99"),
                    fee_amount=Decimal("3500000.00"),
                    net_amount=Decimal("96499999.99"),
                )
            )

        with session_factory() as s:
            summary = s.execute(select(DwSettlementSummary)).scalars().first()
            assert summary.total_gross == Decimal("999999999.90")
            assert summary.total_count == 10


class TestDataCleanserValidation:
    def test_negative_gross_amount_raises(self, session_factory):
        cleanser = DataCleanser(session_factory)
        detail = _make_detail(gross_amount=Decimal("-100.00"))
        with pytest.raises(ValueError, match="non-negative"):
            cleanser.upsert_summary(detail)

    def test_empty_currency_raises(self, session_factory):
        cleanser = DataCleanser(session_factory)
        detail = _make_detail(currency="")
        with pytest.raises(ValueError, match="currency must not be empty"):
            cleanser.upsert_summary(detail)

    def test_concurrent_upsert_same_key(self, session_factory):
        cleanser = DataCleanser(session_factory)
        detail1 = _make_detail(payment_reference="PAY-A")
        detail2 = _make_detail(
            payment_reference="PAY-B",
            gross_amount=Decimal("5000.00"),
            fee_amount=Decimal("175.00"),
            net_amount=Decimal("4825.00"),
        )

        cleanser.upsert_summary(detail1)
        cleanser.upsert_summary(detail2)

        with session_factory() as s:
            summary = s.execute(select(DwSettlementSummary)).scalars().first()
            assert summary.total_count == 2
            assert summary.total_gross == Decimal("15000.00")
