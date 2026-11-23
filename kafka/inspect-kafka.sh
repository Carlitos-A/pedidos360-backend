#!/usr/bin/env bash
set -euo pipefail

# Run from a private host with Kafka CLI and network access to the brokers.
bootstrap="${KAFKA_BOOTSTRAP_SERVERS:?Set KAFKA_BOOTSTRAP_SERVERS}"
topics_cli="${KAFKA_TOPICS_BIN:-kafka-topics.sh}"
configs_cli="${KAFKA_CONFIGS_BIN:-kafka-configs.sh}"
groups_cli="${KAFKA_CONSUMER_GROUPS_BIN:-kafka-consumer-groups.sh}"
command_config=()
if [[ -n "${KAFKA_COMMAND_CONFIG:-}" ]]; then
  command_config=(--command-config "$KAFKA_COMMAND_CONFIG")
fi

case "${1:-}" in
  topics)
    "$topics_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" --list
    ;;
  topic)
    [[ -n "${2:-}" ]] || { echo 'Usage: inspect-kafka.sh topic NAME' >&2; exit 2; }
    "$topics_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" --describe --topic "$2"
    "$configs_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" \
      --entity-type topics --entity-name "$2" --describe --all
    ;;
  groups)
    "$groups_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" --list
    ;;
  group)
    [[ -n "${2:-}" ]] || { echo 'Usage: inspect-kafka.sh group ID' >&2; exit 2; }
    "$groups_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" --describe --group "$2" --state
    "$groups_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" --describe --group "$2" --members --verbose
    "$groups_cli" --bootstrap-server "$bootstrap" "${command_config[@]}" --describe --group "$2" --offsets
    ;;
  *)
    echo 'Usage: inspect-kafka.sh topics|topic NAME|groups|group ID' >&2
    exit 2
    ;;
esac
