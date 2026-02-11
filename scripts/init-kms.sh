#!/bin/bash
set -euo pipefail

ALIAS_NAME="alias/stable-settlement-key"
REGION="ap-northeast-2"

EXISTING_KEY=$(awslocal kms list-aliases \
  --region "$REGION" \
  --query "Aliases[?AliasName=='${ALIAS_NAME}'].TargetKeyId" \
  --output text 2>/dev/null || true)

if [ -n "$EXISTING_KEY" ] && [ "$EXISTING_KEY" != "None" ]; then
  echo "KMS alias ${ALIAS_NAME} already exists (key: ${EXISTING_KEY}), skipping."
  exit 0
fi

echo "Creating KMS key for stable-settlement..."

KEY_ID=$(awslocal kms create-key \
  --key-usage ENCRYPT_DECRYPT \
  --key-spec SYMMETRIC_DEFAULT \
  --region "$REGION" \
  --query 'KeyMetadata.KeyId' \
  --output text)

awslocal kms create-alias \
  --alias-name "$ALIAS_NAME" \
  --target-key-id "$KEY_ID" \
  --region "$REGION"

echo "KMS key created: $KEY_ID (${ALIAS_NAME})"
