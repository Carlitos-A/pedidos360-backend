#!/bin/sh
set -eu

# The allowed alphabet keeps interpolation into SQL string literals safe.
for name in PEDIDOS_DB_PASSWORD PRODUCTOS_DB_PASSWORD REPORT_DB_PASSWORD AUDIT_DB_PASSWORD; do
  eval "value=\${$name:-}"
  if ! printf '%s' "$value" | grep -Eq '^[A-Za-z0-9_@%+=-]{16,}$'; then
    echo "$name must have at least 16 characters using letters, digits, _ @ % + = or -" >&2
    exit 1
  fi
done

export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'pedidos_app'@'%' IDENTIFIED BY '$PEDIDOS_DB_PASSWORD';
CREATE USER IF NOT EXISTS 'productos_app'@'%' IDENTIFIED BY '$PRODUCTOS_DB_PASSWORD';
CREATE USER IF NOT EXISTS 'report_app'@'%' IDENTIFIED BY '$REPORT_DB_PASSWORD';
CREATE USER IF NOT EXISTS 'audit_app'@'%' IDENTIFIED BY '$AUDIT_DB_PASSWORD';
GRANT ALL PRIVILEGES ON pedidosdb.* TO 'pedidos_app'@'%';
GRANT ALL PRIVILEGES ON productosdb.* TO 'productos_app'@'%';
GRANT ALL PRIVILEGES ON reportdb.* TO 'report_app'@'%';
GRANT ALL PRIVILEGES ON auditdb.* TO 'audit_app'@'%';
SQL
