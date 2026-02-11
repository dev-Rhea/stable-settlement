from __future__ import annotations

import datetime
import logging
from decimal import Decimal

from sqlalchemy import and_, select
from sqlalchemy.orm import Session

from src.models import DwSettlementSummary, SettlementDetail

logger = logging.getLogger(__name__)


class DataCleanser:
    def __init__(self, session_factory):
        self._session_factory = session_factory

    def upsert_summary(self, detail: SettlementDetail) -> None:
        self._validate(detail)

        with self._session_factory() as session:
            self._upsert(session, detail)

    @staticmethod
    def _validate(detail: SettlementDetail) -> None:
        if detail.gross_amount < Decimal("0"):
            raise ValueError(
                f"gross_amount must be non-negative, got {detail.gross_amount}"
            )
        if not detail.currency:
            raise ValueError("currency must not be empty")

    @staticmethod
    def _upsert(session: Session, detail: SettlementDetail) -> None:
        summary_date = detail.settled_at.date()
        now = datetime.datetime.now(datetime.timezone.utc)

        stmt = (
            select(DwSettlementSummary)
            .where(
                and_(
                    DwSettlementSummary.summary_date == summary_date,
                    DwSettlementSummary.merchant_id == detail.merchant_id,
                    DwSettlementSummary.currency == detail.currency,
                )
            )
            .with_for_update()
        )
        existing = session.execute(stmt).scalars().first()

        if existing is not None:
            existing.total_count += 1
            existing.total_gross += detail.gross_amount
            existing.total_fee += detail.fee_amount
            existing.total_net += detail.net_amount
            existing.updated_at = now
            logger.info(
                "Updated summary: date=%s, merchant=%s, currency=%s, count=%d",
                summary_date,
                detail.merchant_id,
                detail.currency,
                existing.total_count,
            )
        else:
            summary = DwSettlementSummary(
                summary_date=summary_date,
                merchant_id=detail.merchant_id,
                currency=detail.currency,
                total_count=1,
                total_gross=detail.gross_amount,
                total_fee=detail.fee_amount,
                total_net=detail.net_amount,
                created_at=now,
                updated_at=now,
            )
            session.add(summary)
            logger.info(
                "Created summary: date=%s, merchant=%s, currency=%s",
                summary_date,
                detail.merchant_id,
                detail.currency,
            )

        session.commit()
