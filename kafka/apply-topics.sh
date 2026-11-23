#!/usr/bin/env bash
set -euo pipefail

# Run where kafka-topics.sh and kafka-configs.sh can reach the private brokers.
# Optional KAFKA_COMMAND_CONFIG points to a Kafka client properties file (TLS/SASL).
bootstrap="${KAFKA_BOOTSTRAP_SERVERS:?Set KAFKA_BOOTSTRAP_SERVERS}"
topics_cli="${KAFKA_TOPICS_BIN:-kafka-topics.sh}"
configs_cli="${KAFKA_CONFIGS_BIN:-kafka-configs.sh}"
manifest="${KAFKA_TOPICS_MANIFEST:-$(dirname "$0")/topics.csv}"
command_config=()
if [[ -n "${KAFKA_COMMAND_CONFIG:-}" ]]; then
  command_config=(--command-config "$KAFKA_COMMAND_CONFIG")
fi

while IFS=, read -r topic partitions replicas retention cleanup || [[ -n "$topic" ]]; do
  [[ -z "$topic" || "$topic" == \#* ]] && continue
  cleanup="${cleanup%$'\r'}"
  if [[ ! "$topic" =~ ^[a-zA-Z0-9._-]+$ || ! "$partitions" =~ ^[1-9][0-9]*$ ||
        ! "$replicas" =~ ^[1-9][0-9]*$ || ! "$retention" =~ ^[1-9][0-9]*$ ||
        "$cleanup" != "delete" ]]; then
    echo "Invalid topic manifest row: $topic" >&2
    exit 1
  fi
  topic_list=$("$topics_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" --list)
  if ! grep -Fxq "$topic" <<< "$topic_list"; then
    "$topics_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" \
      --create --if-not-exists --topic "$topic" --partitions "$partitions" \
      --replication-factor "$replicas" \
      --config "retention.ms=$retention" --config "cleanup.policy=$cleanup"
  fi
  description=$("$topics_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" \
      --describe --topic "$topic")
  if [[ ! "$description" =~ PartitionCount:[[:space:]]*([0-9]+) ||
        "${BASH_REMATCH[1]}" != "$partitions" ]]; then
    echo "Partition count differs for $topic; refusing to repartition an active topic" >&2
    exit 1
  fi
  if [[ ! "$description" =~ ReplicationFactor:[[:space:]]*([0-9]+) ||
        "${BASH_REMATCH[1]}" != "$replicas" ]]; then
    echo "Replication factor differs for $topic; fix assignment before continuing" >&2
    exit 1
  fi
  "$configs_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" \
    --entity-type topics --entity-name "$topic" --alter \
    --add-config "retention.ms=$retention,cleanup.policy=$cleanup"
  echo "Verified $topic: partitions=$partitions replicas=$replicas retention.ms=$retention cleanup.policy=$cleanup"
done < "$manifest"
