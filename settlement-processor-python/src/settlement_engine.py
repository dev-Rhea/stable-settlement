from __future__ import annotations

import datetime
import logging
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy import and_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from src.models import MerchantFeePolicy, SettlementDetail

logger = logging.getLogger(__name__)

TWO_PLACES = Decimal("0.01")


class SettlementEngine:
    def __init__(self, session_factory):
        self._session_factory = session_factory

    def process_payment(self, event: dict) -> SettlementDetail | None:
        required = ("receiverId", "amount", "currency", "paymentReference")
        missing = [k for k in required if k not in event]
        if missing:
            raise ValueError(f"Missing required event fields: {missing}")

        merchant_id = int(event["receiverId"])
        gross_amount = Decimal(str(event["amount"]))
        if gross_amount < Decimal("0"):
            raise ValueError(f"amount must be non-negative, got {gross_amount}")

        currency = str(event["currency"]).strip()
        if not currency:
            raise ValueError("currency must not be empty")

        payment_reference = str(event["paymentReference"]).strip()
        if not payment_reference:
            raise ValueError("paymentReference must not be empty")

        with self._session_factory() as session:
            return self._settle(
                session, payment_reference, merchant_id, gross_amount, currency
            )

    def _settle(
        self,
        session: Session,
        payment_reference: str,
        merchant_id: int,
        gross_amount: Decimal,
        currency: str,
    ) -> SettlementDetail | None:
        today = datetime.date.today()
        policy = self._find_policy(session, merchant_id, currency, today)
        if policy is None:
            raise ValueError(
                f"No fee policy found for merchant={merchant_id}, "
                f"currency={currency}, date={today}"
            )

        fee_amount = self._calculate_fee(gross_amount, policy)
        net_amount = (gross_amount - fee_amount).quantize(
            TWO_PLACES, rounding=ROUND_HALF_UP
        )

        detail = SettlementDetail(
            payment_reference=payment_reference,
            merchant_id=merchant_id,
            gross_amount=gross_amount,
            fee_amount=fee_amount,
            net_amount=net_amount,
            fee_type=policy.fee_type,
            fee_rate=policy.fee_value,
            currency=currency,
            settled_at=datetime.datetime.now(datetime.timezone.utc),
        )

        try:
            session.add(detail)
            session.commit()
            session.refresh(detail)
            session.expunge(detail)
            logger.info(
                "Settlement created: ref=%s, gross=%s, fee=%s, net=%s",
                payment_reference,
                gross_amount,
                fee_amount,
                net_amount,
            )
            return detail
        except IntegrityError:
            session.rollback()
            logger.warning(
                "Duplicate payment_reference=%s, skipping", payment_reference
            )
            return None

    @staticmethod
    def _find_policy(
        session: Session,
        merchant_id: int,
        currency: str,
        today: datetime.date,
    ) -> MerchantFeePolicy | None:
        stmt = (
            select(MerchantFeePolicy)
            .where(
                and_(
                    MerchantFeePolicy.merchant_id == merchant_id,
                    MerchantFeePolicy.currency == currency,
                    MerchantFeePolicy.effective_from <= today,
                    (
                        (MerchantFeePolicy.effective_to.is_(None))
                        | (MerchantFeePolicy.effective_to >= today)
                    ),
                )
            )
            .order_by(MerchantFeePolicy.effective_from.desc())
        )
        return session.execute(stmt).scalars().first()

    @staticmethod
    def _calculate_fee(
        gross_amount: Decimal, policy: MerchantFeePolicy
    ) -> Decimal:
        if policy.fee_type == "FIXED":
            return Decimal(str(policy.fee_value)).quantize(
                TWO_PLACES, rounding=ROUND_HALF_UP
            )

        fee = (gross_amount * Decimal(str(policy.fee_value))).quantize(
            TWO_PLACES, rounding=ROUND_HALF_UP
        )

        if policy.min_fee is not None:
            min_fee = Decimal(str(policy.min_fee))
            fee = max(fee, min_fee)

        if policy.max_fee is not None:
            max_fee = Decimal(str(policy.max_fee))
            fee = min(fee, max_fee)

        return fee
