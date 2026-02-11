from __future__ import annotations

import datetime
from decimal import Decimal
from typing import Optional

from sqlalchemy import Date, DateTime, Index, Numeric, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class MerchantFeePolicy(Base):
    __tablename__ = "merchant_fee_policy"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    merchant_id: Mapped[int] = mapped_column(nullable=False)
    fee_type: Mapped[str] = mapped_column(String(20), nullable=False)
    fee_value: Mapped[Decimal] = mapped_column(Numeric(10, 4), nullable=False)
    currency: Mapped[str] = mapped_column(String(10), nullable=False)
    min_fee: Mapped[Optional[Decimal]] = mapped_column(Numeric(20, 2), nullable=True)
    max_fee: Mapped[Optional[Decimal]] = mapped_column(Numeric(20, 2), nullable=True)
    effective_from: Mapped[datetime.date] = mapped_column(Date, nullable=False)
    effective_to: Mapped[Optional[datetime.date]] = mapped_column(
        Date, nullable=True
    )

    __table_args__ = (
        UniqueConstraint(
            "merchant_id",
            "currency",
            "effective_from",
            name="uk_merchant_currency_effective",
        ),
    )


class SettlementDetail(Base):
    __tablename__ = "settlement_detail"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    payment_reference: Mapped[str] = mapped_column(
        String(255), nullable=False, unique=True
    )
    merchant_id: Mapped[int] = mapped_column(nullable=False)
    gross_amount: Mapped[Decimal] = mapped_column(Numeric(20, 2), nullable=False)
    fee_amount: Mapped[Decimal] = mapped_column(Numeric(20, 2), nullable=False)
    net_amount: Mapped[Decimal] = mapped_column(Numeric(20, 2), nullable=False)
    fee_type: Mapped[str] = mapped_column(String(20), nullable=False)
    fee_rate: Mapped[Decimal] = mapped_column(Numeric(10, 4), nullable=False)
    currency: Mapped[str] = mapped_column(String(10), nullable=False)
    settled_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=False), nullable=False
    )

    __table_args__ = (
        Index("idx_settlement_merchant", "merchant_id"),
        Index("idx_settlement_settled", "settled_at"),
    )


class DwSettlementSummary(Base):
    __tablename__ = "dw_settlement_summary"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    summary_date: Mapped[datetime.date] = mapped_column(Date, nullable=False)
    merchant_id: Mapped[int] = mapped_column(nullable=False)
    currency: Mapped[str] = mapped_column(String(10), nullable=False)
    total_count: Mapped[int] = mapped_column(nullable=False, default=0)
    total_gross: Mapped[Decimal] = mapped_column(
        Numeric(20, 2), nullable=False, default=Decimal("0")
    )
    total_fee: Mapped[Decimal] = mapped_column(
        Numeric(20, 2), nullable=False, default=Decimal("0")
    )
    total_net: Mapped[Decimal] = mapped_column(
        Numeric(20, 2), nullable=False, default=Decimal("0")
    )
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=False), nullable=False
    )
    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=False), nullable=False
    )

    __table_args__ = (
        UniqueConstraint(
            "summary_date",
            "merchant_id",
            "currency",
            name="uk_summary_date_merchant_currency",
        ),
    )


class MerchantSensitiveData(Base):
    __tablename__ = "merchant_sensitive_data"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    merchant_id: Mapped[int] = mapped_column(nullable=False)
    encrypted_account_info: Mapped[str] = mapped_column(Text, nullable=False)
    encrypted_data_key: Mapped[str] = mapped_column(Text, nullable=False)
    kms_key_id: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=False), nullable=False
    )
    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=False), nullable=False
    )

    __table_args__ = (
        UniqueConstraint("merchant_id", name="uk_merchant_sensitive"),
    )
