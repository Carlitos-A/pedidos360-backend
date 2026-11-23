#!/usr/bin/env bash
set -euo pipefail

# Read one rejected Kafka record from a private operator host. Output contains raw payload.
bootstrap="${KAFKA_BOOTSTRAP_SERVERS:?Set KAFKA_BOOTSTRAP_SERVERS}"
topic="${1:-}"
partition="${2:-}"
offset="${3:-}"
audit_dlt="${AUDIT_DLT_TOPIC:-pedidos360.audit.dlt.v1}"
report_dlt="${REPORT_DLT_TOPIC:-pedidos360.report.dlt.v1}"
if [[ "$topic" != "$audit_dlt" && "$topic" != "$report_dlt" ]] ||
   [[ ! "$partition" =~ ^[0-9]+$ ]] || [[ ! "$offset" =~ ^[0-9]+$ ]]; then
  echo 'Usage: inspect-dlt.sh AUDIT_OR_REPORT_DLT_TOPIC PARTITION OFFSET' >&2
  exit 2
fi
command_config=()
if [[ -n "${KAFKA_COMMAND_CONFIG:-}" ]]; then
  command_config=(--command-config "$KAFKA_COMMAND_CONFIG")
fi
"${KAFKA_CONSOLE_CONSUMER_BIN:-kafka-console-consumer.sh}" \
  --bootstrap-server "$bootstrap" "${command_config[@]}" --topic "$topic" \
  --partition "$partition" --offset "$offset" --max-messages 1 \
  --property print.headers=true --property print.key=true
