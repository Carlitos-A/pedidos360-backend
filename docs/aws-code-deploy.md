# Código preparado para un despliegue AWS nuevo

Este documento describe el orden de arranque del código. La VPC, RDS, EC2,
Security Groups, ALB, API Gateway y Entra se configuran después. La SPA actual
se publica en GitHub Pages.
No se deben aplicar los SQL históricos fechados a estas bases nuevas.

## 1. MySQL

Crear la instancia MySQL 8 de RDS sin acceso público. Ejecutar
[`db/001_create_databases.sql`](../db/001_create_databases.sql) como
administrador. Crear cuatro usuarios, cada uno con acceso **solo a su base**:
`pedidos_app` a `pedidosdb`, `productos_app` a `productosdb`,
`report_app` a `reportdb` y `audit_app` a `auditdb`.
La primera ejecución necesita permisos DDL para que Flyway cree sus tablas
y `flyway_schema_history`; los reinicios siguientes no repiten V1.
Conservar las contraseñas fuera de Git.

Cada servicio ejecuta automáticamente su migración inicial al iniciar con
`prod`. Luego Hibernate valida las tablas. Pedidos y productos también usan
`ddl-auto: validate` en producción. En desarrollo H2 mantiene
`ddl-auto: update` y Flyway deshabilitado. No habilitar
`spring.flyway.baseline-on-migrate` automáticamente sobre datos existentes.
`pedidos-service` ejecuta además `V2__registered_customers.sql` para crear el
registro local de clientes. `audit-service` elimina la tabla de cursor del
adaptador de Graph retirado mediante su propia migración V2.

Los SQL de `*/db/migrations/2026-*.sql` son **históricos**. Si se migra una
base antigua, respaldarla, revisar su esquema real y aplicar los cambios
manualmente antes de registrar un baseline Flyway verificado. Orden orientativo:

| Base histórica | Orden de scripts |
|---|---|
| `pedidosdb` | `2026-11-18-order-status`, `2026-11-19-order-history-outbox`, `2026-11-19-order-money`, `2026-11-21-optional-order-work`, `2026-11-21-rejected-rabbit` |
| `productosdb` | `2026-11-19-price-clp`, `2026-11-20-product-audit-identity` |
| `reportdb` | `2026-11-21-report-projection` |
| `auditdb` | `2026-11-20-audit-service`, `2026-11-21-rejected-kafka` |

Los scripts históricos presuponen tablas creadas por versiones anteriores de
Hibernate; no forman una instalación desde cero. El de auditoría incluye una
copia opcional desde `pedidosdb`, que no corresponde en una base vacía.

## 2. Broker y tópicos

En el host privado de mensajería:

```bash
cp .env.example .env
# Completar credenciales y la IP privada anunciada por Kafka.
docker compose -f docker-compose.yml up -d --wait
docker compose -f docker-compose.kafka.yml up -d --wait
docker compose -f docker-compose.kafka.yml exec -e KAFKA_BOOTSTRAP_SERVERS=broker1:29092,broker2:29092,broker3:29092 -e KAFKA_TOPICS_BIN=kafka-topics -e KAFKA_CONFIGS_BIN=kafka-configs broker1 bash /opt/pedidos360/kafka/apply-topics.sh
```

ZooKeeper 1/2/3 y broker 1/2/3 persisten en volúmenes distintos. La
configuración de los tópicos, particiones, réplicas y retención vive en
`kafka/topics.csv`. Kafka-UI escucha en el host en
`127.0.0.1:8080` por defecto: acceder por túnel SSM desde una máquina
autorizada; no publicar ese puerto por Internet. Con aplicaciones en otra EC2,
`KAFKA_ADVERTISED_HOST` debe ser la IP o DNS **privados** del host de Kafka,
`KAFKA_BIND_ADDRESS` debe permitir la conexión y el Security Group limita
9092–9094 a las aplicaciones. RabbitMQ sigue la misma regla para 5672.

Esta topología de evaluación tiene tres réplicas lógicas en un solo host;
la caída de ese host sigue siendo una caída total. Para MSK, usar
`prod,msk` y sus endpoints IAM, sin el Compose Kafka.

## 3. Aplicaciones

En la EC2 privada de aplicaciones:

```bash
cp .env.apps.example .env.apps
# Reemplazar todos los valores de ejemplo; APP_BIND_ADDRESS = IP privada de esta EC2.
chmod 600 .env.apps
docker compose --env-file .env.apps -f docker-compose.apps.yml config --quiet
docker compose --env-file .env.apps -f docker-compose.apps.yml up -d --build
```

El Compose levanta pedidos 8081, productos 8082, rabbit-admin 8083, audit
8084, report 8085 y kafka-admin 8086. Los servicios Kafka usan
`prod,kafka-compose`; rabbit-admin usa `prod`. Los puertos se vinculan a
`APP_BIND_ADDRESS`; en AWS asignar la IP privada de la EC2. El Security
Group de aplicaciones debe admitir esos puertos **solo desde el ALB interno**
y, para administración, desde los orígenes internos expresamente previstos.
Las reglas del ALB/API Gateway no deben enrutar `/internal/*` al navegador.
`/internal/kafka/*` además requiere Admin y `X-Internal-Key`.

Cada target group del ALB usa `GET /internal/live` en su puerto; devuelve
HTTP 200 sin JWT ni `X-Internal-Key` si el proceso Java responde.
`/internal/health` sigue requiriendo la clave y comprueba dependencias para
la vista Admin. El liveness depende del aislamiento de red del Security Group,
no de una clave HTTP.

En el gateway, dirigir `/api/orders/*`, `/api/health`,
`GET /api/customers` y `POST /api/customers/me` a 8081,
`/api/products/*` a 8082, `/api/reports/*` a 8085,
`/api/audit/*` a 8084. Los admin en 8083/8086 quedan internos; no
agregar rutas públicas para ellos. El gateway debe validar JWT y CORS, y
los servicios conservan su control por rol y scope. El registro propio del
Cliente requiere `pedidos.read`; las escrituras administrativas requieren
`pedidos.write`. No se configura Microsoft Graph para este flujo.

## 4. Frontend

La SPA actual vive en GitHub Pages en
`https://carlitos-a.github.io/pedidos360-frontend/`. Compilar Angular con
`SPA_BASE_URL` igual a esa URL, incluida la ruta del repositorio. En el
backend, `FRONT_URL=https://carlitos-a.github.io` (solo el origen) para CORS.
Mantener los redirect URI de Entra en `/auth/callback` y `/login` bajo la ruta
del repositorio; configurar el gateway con el mismo origen permitido.
