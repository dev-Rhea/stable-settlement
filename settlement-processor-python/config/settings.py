from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    db_url: str
    kafka_bootstrap_servers: str
    kafka_group_id: str
    kafka_topic: str
    kms_enabled: bool
    aws_region: str
    aws_kms_key_id: str
    aws_endpoint_url: str | None

    @classmethod
    def from_env(cls) -> Settings:
        db_url = os.environ.get("DB_URL")
        if not db_url:
            raise EnvironmentError("DB_URL environment variable is required")

        return cls(
            db_url=db_url,
            kafka_bootstrap_servers=os.environ.get(
                "KAFKA_BOOTSTRAP_SERVERS", "kafka:29092"
            ),
            kafka_group_id=os.environ.get(
                "KAFKA_GROUP_ID", "settlement-processor-group"
            ),
            kafka_topic=os.environ.get("KAFKA_TOPIC", "payment-events"),
            kms_enabled=os.environ.get("KMS_ENABLED", "false").lower() == "true",
            aws_region=os.environ.get("AWS_REGION", "ap-northeast-2"),
            aws_kms_key_id=os.environ.get(
                "AWS_KMS_KEY_ID", "alias/stable-settlement-key"
            ),
            aws_endpoint_url=os.environ.get("AWS_ENDPOINT_URL"),
        )
