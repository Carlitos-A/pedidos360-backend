# Pedidos360 — Backend

Microservicios Spring Boot del sistema Pedidos360 (EP1 · DSY1107).

## Auditoría en Kafka (código preparado)

`audit-service` consume `pedidos360.audit.v1` y persiste su propia tabla
`audit_event`. Expone el mismo contrato `GET /api/audit/events` que usa Angular,
con filtros y paginación en SQL, orden `occurredAt DESC, id DESC`, fechas
inclusivas en `America/Santiago` y acceso exclusivo para Admin. En producción
usa MySQL `auditdb`; localmente usa H2. El JAR se compila con
`mvn -f audit-service/pom.xml package`.

`pedidos-service` conserva RabbitMQ para notificación y ticket. Sus eventos de
auditoría se colocan en el outbox transaccional existente. `productos-service`
usa su outbox de auditoría y registra el destino al crear cada entrada. Con
`AUDIT_KAFKA_ENABLED=false` (valor inicial) ambos publican por RabbitMQ; con
`true` publican en Kafka y esperan confirmación antes de retirar el outbox.
Una entrada pendiente conserva su destino aunque cambie el flag. Los eventos
Kafka contienen `schemaVersion`, `eventId`, `type`, `entityId`, `occurredAt`
UTC y actor; cambios de estado incluyen `previousStatus` y `status`. No se
incluyen nombre, detalle ni ítems del pedido. El consumidor usa `eventId` único
para deduplicar. Los mensajes inválidos se confirman solo tras publicarse en
`pedidos360.audit.dlt.v1`; fallos de base o de DLT se reintentan sin saltar el
offset. `pedidos360.login.v1` está reservado para inicios de sesión de Entra.
Un adaptador opcional de `audit-service` consulta Microsoft Graph por inicios
interactivos exitosos de la SPA y publica `USER_LOGIN` con el ID de registro de
Entra como clave estable. Conserva un cursor en MySQL, reconsulta una ventana
solapada y no lo avanza si falla la publicación. Angular no emite login.

Variables compartidas: `KAFKA_BOOTSTRAP_SERVERS`, `AUDIT_KAFKA_ENABLED` y
`AUDIT_KAFKA_TOPIC`. `audit-service` acepta `AUDIT_LOGIN_TOPIC`,
`AUDIT_DLT_TOPIC`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` y
`FRONT_URL`. `INTERNAL_SERVICE_KEY` protege `/internal/health`; cuando
`pedidos-service` recibe `AUDIT_SERVICE_URL` con la URL interna de ese endpoint,
`/api/health` consulta al nuevo servicio en vez de la auditoría antigua.
No enrutar `/internal/health` en API Gateway ni exponer el puerto 8084 a Internet.
El perfil `prod` configura `SASL_SSL`/`AWS_MSK_IAM`; las
credenciales provienen del rol de ejecución AWS. Los clientes de pedidos y
productos necesitan escribir en el tópico de auditoría; audit-service necesita
leer los tópicos de auditoría y login con su grupo, y escribir en DLT. El
frontend nunca se conecta a Kafka.

El adaptador de login permanece desactivado (`ENTRA_SIGNINS_ENABLED=false`).
Para activarlo se requieren `ENTRA_TENANT_ID`, `ENTRA_SPA_APP_ID`,
`GRAPH_CLIENT_ID`, `GRAPH_CLIENT_SECRET` y el permiso de aplicación de Microsoft
Graph `AuditLog.Read.All` con consentimiento administrativo. Microsoft Graph
exige licencia Entra ID P1 o P2 para descargar sign-ins. Se pueden ajustar
`ENTRA_INITIAL_LOOKBACK_HOURS` y `ENTRA_SIGNIN_OVERLAP_HOURS` (24 h por defecto);
la disponibilidad histórica depende de la retención de los registros Entra.

Antes del corte de ruta, aplicar
[`2026-11-20-product-audit-identity.sql`](productos-service/db/migrations/2026-11-20-product-audit-identity.sql)
en `productosdb` y
[`2026-11-20-audit-service.sql`](audit-service/db/migrations/2026-11-20-audit-service.sql)
en `auditdb` de la misma instancia MySQL. La segunda migración copia eventos
legados usando `event_key` como `eventId`; repetir la copia tras activar ambos
productores y antes de dirigir `/api/audit/events` al nuevo servicio. La
inserción ignora claves ya copiadas. Configurar la sesión MySQL en UTC durante
la copia y verificar conteos antes del corte. La retención de la base se activa
con `AUDIT_RETENTION_DAYS`; el valor inicial `0` no elimina datos hasta definir
la política. La retención del tópico Kafka se configura por separado.

## Microservicios

### Contrato `/api/*` para Angular

La vista Admin de reportes y auditoría y el panel de estado ya existen en el
frontend. Reportes y salud viven en `pedidos-service`:
`GET /api/reports/{kpis,sales,lead-time,top-products}` y `GET /api/health`.
`GET /api/audit/events` sigue disponible en `pedidos-service` durante la
migración; después del corte, el Gateway debe dirigirlo a `audit-service:8084`.
Todos requieren rol Admin.

Los reportes cuentan pedidos completados para ventas y excluyen cancelados.
`activeUsers` queda `null` porque no hay fuente confiable; el lead time solo se
calcula si existe un evento COMPLETED persistido. Auditoría guarda creación y
cambios de estado de pedidos en la misma base de datos. Productos publica
actualizaciones en RabbitMQ (`products.updated.audit`) desde un outbox local
con reintentos mientras `AUDIT_KAFKA_ENABLED=false`. El código Kafka se describe
en la sección «Auditoría en Kafka».

`/api/health` informa `bff: DOWN` porque no hay BFF. `orders` y `report`
comprueban la base de datos local; `products` comprueba que el servicio
protegido responde 401 sin token. Con `AUDIT_SERVICE_URL`, `audit` consulta
la salud interna de `audit-service`; sin esa URL conserva la comprobación
anterior de base de datos y RabbitMQ.
`report` sigue siendo un módulo dentro de pedidos. `audit-service` es un
proceso independiente preparado para el corte a Kafka.

`pedidos-service` expone `GET/POST /api/orders`, `GET/PUT /api/orders/{id}` y
`PATCH /api/orders/{id}/status`. La lista acepta `status`, `q`, `from`, `to`,
`page` (desde cero) y `pageSize` (1 a 100), y responde `{items,total,page,pageSize}`.
Cada pedido devuelve `version`. Para cambiar el estado, Admin envía
`PATCH /api/orders/{id}/status` con `{ "status": "ACCEPTED", "expectedVersion": 0 }`.
Si el pedido cambió desde la lectura, la API responde 409 y Angular recarga el
detalle. PUT solo cambia los datos del pedido; no cambia su estado. El servidor
asigna CREATED al crear. Las transiciones admitidas son CREATED a ACCEPTED o
CANCELLED, ACCEPTED a IN_PROGRESS o CANCELLED, e IN_PROGRESS a COMPLETED o
CANCELLED. COMPLETED y CANCELLED son terminales.

Los pedidos nuevos requieren al menos un ítem. `POST /api/orders` recibe además
`requestId` (UUID de idempotencia) y `PUT /api/orders/{id}` recibe
`expectedVersion`; ambos aceptan el total mostrado por Angular, pero el servidor
lo ignora. Productos confirma propietario, precio CLP entero y stock en
`PUT /internal/reservations/{requestId}` y devuelve los nombres, precios y total
que se guardan como snapshot. Repetir la misma reserva no descuenta dos veces;
editar cantidades ajusta la diferencia y cancelar libera el stock. Si productos
no responde, el pedido nuevo queda pendiente, invisible en la API pública, y
un trabajo programado reintenta la confirmación. Las ediciones pendientes se
reintentan del mismo modo; el estado y la reasignación quedan bloqueados mientras
exista una edición pendiente.

Configura el mismo `INTERNAL_SERVICE_KEY` no vacío en ambos servicios y
`PRODUCTS_INTERNAL_URL` en pedidos (por ejemplo, `http://localhost:8082` en la
EC2). La ruta `/internal/*` debe quedar fuera del API Gateway y de Internet;
solo pedidos debe poder alcanzar el puerto 8082. La clave viaja por header
entre servicios. La API pública de productos devuelve precios CLP enteros.
El inventario y el pedido viven en bases distintas: la reserva es idempotente y
los cambios pendientes se recuperan tras una caída; no hay una transacción SQL
distribuida. Una cancelación confirma primero el estado y luego libera stock;
si productos está caído, el trabajo programado vuelve a intentar la liberación.
El rol Cliente recibe solo pedidos cuyos `customerId` y `customerTenantId`
coinciden con `oid` y `tid` del access token. Los registros históricos sin
propietario solo son visibles para Admin. `POST /pedidos` ya no se usa, pues
permitía crear registros sin propietario ni validación; `GET /pedidos` queda
temporalmente disponible solo para Admin.

La lista de Cliente aplica propietario, búsqueda, estado y fechas en SQL antes
de paginar y contar. Admin selecciona el destinatario desde
`GET /api/customers?q=...`; `POST /api/orders` vuelve a comprobar en Microsoft
Graph que la cuenta existe, está habilitada y tiene asignado directamente el
rol Cliente de esta API. PUT no puede cambiar propietario. Para cambiarlo,
Admin usa `PATCH /api/orders/{id}/owner` con `customerId` y
`customerTenantId`; la operación se audita. Debes enrutar también
`/api/customers` hacia `pedidos-service:8081` en API Gateway.

Para activar el directorio configura `ENTRA_TENANT_ID`, `GRAPH_CLIENT_ID`,
`GRAPH_CLIENT_SECRET`, `API_SERVICE_PRINCIPAL_ID` y `CUSTOMER_ROLE_ID` en la
instancia. La aplicación confidencial de Graph necesita permiso de aplicación
`Directory.Read.All` con consentimiento de administrador para leer usuarios y
asignaciones directas de roles. Si falta configuración o Graph falla, la
búsqueda y las asignaciones responden 503. No guardes el secreto en Git.

La migración histórica es explícita: Admin envía a
`POST /api/orders/ownership-migration` una lista de hasta 100 objetos
`{orderId,customerId,customerTenantId}` construida con una correspondencia
comprobada. El servidor vuelve a verificar cada cuenta en Graph, exige que
el pedido aún no tenga propietario, guarda la correspondencia en
`verified_owner_mapping` con actor y fecha, y registra auditoría. Un error
revierte el lote completo. Nunca se asigna por coincidencia del nombre.

`productos-service` expone `GET/POST /api/products`, `PUT /api/products/{id}`
y `PATCH /api/products/{id}/stock` con cuerpo `{ "delta": -1 }`. Cada producto
guarda `customerId` y `customerTenantId`; Admin asigna ambos al crear o editar.
GET `/api/products` devuelve todos para Admin y solo los que coinciden con
`oid` y `tid` del access token para Cliente. Los productos antiguos sin dueño
quedan visibles solo para Admin hasta una asignación verificada. El GET legado
`/productos` requiere Admin y el POST legado se deshabilitó para impedir
productos nuevos sin dueño. Ambos servicios admiten `PATCH` en CORS.

Antes de desplegar sobre MySQL existente, preparar migración de esquema para
propietario, `version`, ítems, historial y stock. Las filas existentes no deben
recibir un propietario inferido por nombre; asignarlo solo tras verificarlo.
Tras respaldar la base y desplegar la columna `version`, ejecutar
[`2026-11-18-order-status.sql`](pedidos-service/db/migrations/2026-11-18-order-status.sql)
en la base de pedidos. Convierte PENDIENTE a CREATED, EN_PREPARACION a
IN_PROGRESS y ENTREGADO a COMPLETED; inicializa versiones nulas en 0. El
script lista los valores sin equivalencia para revisión y no los modifica.
La API conserva esos valores y Angular los muestra como «SIN EQUIVALENCIA»;
no permite avanzar el estado hasta resolverlos. No se deducen ACCEPTED ni
CANCELLED a partir de datos antiguos.
Después de añadir las columnas nuevas, ejecutar
[`2026-11-19-price-clp.sql`](productos-service/db/migrations/2026-11-19-price-clp.sql)
en productosdb y
[`2026-11-19-order-money.sql`](pedidos-service/db/migrations/2026-11-19-order-money.sql)
en pedidosdb. Convierten solo importes enteros exactos; los fraccionarios se
revisan manualmente. Los pedidos históricos con `fecha` sin zona conocida
conservan ese dato, pero no reciben un `createdAt` inventado. Los pedidos nuevos
guardan `created_at_utc` y responden fechas ISO 8601 UTC.
El API Gateway aún debe enrutar `/api/orders/*` y
`/api/products/*` a los puertos internos respectivos.

| Servicio | Puerto | Endpoints |
|---|---|---|
| `pedidos-service` | 8081 | `/api/orders`, `/api/reports/*`, `/api/audit/events`, `/api/health` |
| `productos-service` | 8082 | `/api/products`, `/api/products/{id}/stock` |
| `rabbit-admin-service` | 8083 | `GET /admin/health`, gestión REST de colas, exchanges y bindings |
| `audit-service` | 8084 | `GET /api/audit/events`, `GET /internal/health` |

## Requisitos

- Java JDK 21
- Maven (incluido vía `mvnw.cmd`)

## Configuración de identidad

Los servicios existentes ya apuntan al tenant y al audience de Entra ID usados por el frontend. Antes de desplegar, comprueba `issuer-uri` y `app.security.audience` en `application.yml`; para producción usa las variables de entorno y el perfil `prod`.

## Ejecutar

```powershell
# En Windows, desde cada carpeta de servicio:
mvnw.cmd spring-boot:run
```

## Seguridad

- Valida firma, emisor (issuer) y vigencia del JWT contra Microsoft Entra ID.
- Valida audience (`aud`) con `AudienceValidator`.
- Autorización por rol: claim `roles` → autoridades `APPROLE_*`.
  - `GET` → Admin o Cliente
  - `POST` → solo Admin
- Sin token → 401 · sin permiso → 403.
- CORS habilitado para `http://localhost:4200` en desarrollo; en producción se configura con `FRONT_URL` (el sitio de GitHub Pages).

### CORS, rutas y scope de escritura

El origen de GitHub Pages es `https://carlitos-a.github.io` (sin la ruta del
repositorio). `pedidos-service` y `productos-service` aceptan GET, POST, PUT,
PATCH y OPTIONS con `Authorization` y `Content-Type`. En producción, `FRONT_URL`
puede sobrescribir ese origen; para varios orígenes usa una lista separada por
comas. El API Gateway necesita su propia configuración CORS. Con un perfil AWS
CLI autorizado, ejecuta [`configure-api-gateway-cors.ps1`](scripts/configure-api-gateway-cors.ps1)
para conservar los orígenes actuales y añadir PUT/PATCH y los dos headers.

Después, revisa las rutas de la HTTP API `4r80bd3omi` en `us-east-1`: POST
`/api/orders`, PUT `/api/orders/{id}`, PATCH `/api/orders/{id}/status`, PATCH
`/api/orders/{id}/owner`, POST `/api/orders/ownership-migration`, POST
`/api/products`, PUT `/api/products/{id}` y PATCH
`/api/products/{id}/stock`. Cada ruta debe apuntar a la integración interna de
su servicio y usar el JWT authorizer; la ruta `/internal/*` no debe publicarse.
El script solo modifica CORS porque no puede deducir con seguridad las
integraciones y el authorizer de rutas que aún no se han inspeccionado en AWS.

Para exigir el permiso delegado, expón `pedidos.write` en la App Registration
de la API, añádelo como permiso delegado de la SPA y concede consentimiento.
Configura `pedidos.write` como único scope de autorización en las rutas de
escritura del gateway y conserva `pedidos.read` en GET. API Gateway acepta una
coincidencia con **cualquiera** de los scopes de una ruta; incluir ambos en
una ruta de escritura permitiría entrar con solo lectura. Una vez que Entra y
el gateway estén listos, activa `REQUIRE_WRITE_SCOPE=true` en pedidos y
productos. Así, cada POST/PUT/PATCH exige simultáneamente rol `Admin` y
`scp` con `pedidos.write`. Hasta esa activación sigue exigiendo rol `Admin`.
Angular pide `pedidos.read` para iniciar sesión y GET, y `pedidos.write` solo
para métodos de escritura. Un Cliente podría recibir el scope delegado de la
SPA, pero su rol sigue bloqueando las escrituras.

## Base de datos

RDS MySQL en producción y H2 en memoria para desarrollo, con credenciales por variables de entorno.

## EP4: mensajería asíncrona con RabbitMQ

`pedidos-service` guarda el historial de estados junto con el pedido. Cada evento nuevo incluye estado previo y nuevo, fecha UTC del servidor, nombre, rol, `oid` y `tid` del Admin autenticado. La creación tiene estado previo nulo. Pedidos históricos sin eventos conservan `history: []`; los eventos antiguos no reciben identidad ni estado previo inventados.

La mensajería nueva usa `schemaVersion: 2`: cada evento incluye `eventId`, propietario (`customerId` y `customerTenantId`), ítems, total CLP, actor y fecha UTC. Los consumidores siguen aceptando el `OrderCreatedEvent` antiguo sin versión. El evento se escribe en `order_outbox` en la misma transacción que el pedido y el registro de auditoría. Un trabajo programado lo publica con confirmación de RabbitMQ y reintenta fallos; una caída después de la confirmación puede producir una segunda entrega con el mismo `eventId`, por lo que los consumidores con efectos deben deduplicar por evento y destino. Notificación y ticket aún solo registran el procesamiento en el log; los reportes actuales leen la base de pedidos.

| Caso | Exchange y tipo | Routing key | Cola | DLQ |
|---|---|---|---|---|
| Notificación | `pedidos360.orders.direct` (direct) | `orders.created` | `pedidos360.orders.notification` | `pedidos360.orders.notification.dlq` |
| Ticket | `pedidos360.orders.topic` (topic) | `orders.created.ticket` | `pedidos360.orders.ticket` | `pedidos360.orders.ticket.dlq` |
| Auditoría | `pedidos360.orders.topic` (topic) | `orders.created.audit` | `pedidos360.orders.audit` | `pedidos360.orders.audit.dlq` |
| Cambio de estado | `pedidos360.orders.topic` (topic) | `orders.status.changed` | `pedidos360.orders.audit` | `pedidos360.orders.audit.dlq` |
| Cancelación | `pedidos360.orders.topic` (topic) | `orders.cancelled` | `pedidos360.orders.audit` | `pedidos360.orders.audit.dlq` |

Nombres y claves se centralizan en `pedidos-service/src/main/resources/application.yml`. Los listeners usan ACK manual; cuando el payload no es procesable, hacen NACK sin requeue y RabbitMQ enruta el mensaje a su DLQ. En producción, ejecutar [`2026-11-19-order-history-outbox.sql`](pedidos-service/db/migrations/2026-11-19-order-history-outbox.sql) en pedidosdb antes de activar el código. Los perfiles local y prod aún usan `ddl-auto: update`.

### Levantar el clúster local

Requisitos: Docker Desktop con Docker Compose v2.

```powershell
Copy-Item .env.example .env
# Edita .env y reemplaza la cookie y contraseña de ejemplo por valores aleatorios.
docker compose up -d
docker compose ps
docker exec pedidos360-rabbit1 rabbitmqctl cluster_status
```

La consola de RabbitMQ queda en `http://localhost:15672` (usuario y clave de `.env`). El segundo nodo expone la consola local en `http://localhost:15673`. Ambos forman el clúster dentro de la red Docker `pedidos360-messaging`. Por defecto el puerto AMQP se publica en `127.0.0.1`; en el host dedicado de AWS, cambia `RABBITMQ_BIND_ADDRESS` a la IP privada de esa instancia para permitir que el backend se conecte por la VPC. La consola web sigue solo en loopback y los nodos se comunican por la red interna de Docker.

Arranca después `pedidos-service` y `rabbit-admin-service`. Sus valores locales apuntan a `localhost:5672`, vhost `/pedidos360`, usuario `pedidos360` y clave `pedidos360-local`; configura esos mismos valores en `.env` para desarrollo local. No uses esa contraseña en AWS.

```powershell
cd pedidos-service
./mvnw.cmd spring-boot:run
```

En otra terminal, desde `rabbit-admin-service`, ejecuta `..\pedidos-service\mvnw.cmd -f pom.xml spring-boot:run`. Arranca también `productos-service` y configura `INTERNAL_SERVICE_KEY` en ambos servicios. Al crear un pedido con `POST /api/orders`, los tres listeners procesan el evento; se observan sus logs en la terminal del servicio y sus colas en la consola RabbitMQ.

### API de administración

El nuevo `rabbit-admin-service` escucha en el puerto `8083` y requiere un JWT de Entra ID con rol `Admin`; también valida issuer, audience, firma y vigencia como los otros servicios.

- `POST /admin/queues`: crea una cola durable y opcionalmente configura DLX/routing key.
- `DELETE /admin/queues/{name}`: elimina únicamente nombres con prefijo `pedidos360.dynamic.`.
- `POST /admin/exchanges`: crea exchanges `direct`, `topic` o `fanout` bajo el prefijo `pedidos360.dynamic.`.
- `DELETE /admin/exchanges/{name}`: elimina únicamente exchanges dinámicos.
- `POST /admin/bindings`: crea un binding entre una cola y un exchange dinámicos.
- `DELETE /admin/bindings`: elimina ese binding; recibe el mismo JSON.
- `GET /admin/health`: endpoint de salud, protegido por rol Admin.

Ejemplo de cuerpo para crear una cola administrada:

```json
{
  "name": "pedidos360.dynamic.demo",
  "durable": true,
  "deadLetterExchange": "pedidos360.dead-letter",
  "deadLetterRoutingKey": "pedidos360.dynamic.demo"
}
```

La API valida los nombres y no permite borrar la topología fija del sistema. Las operaciones administrativas deben exponerse en API Gateway con autorización por JWT y rol; no publiques el puerto 8083 directamente a Internet.

El JSON para bindings es `{"queue":"pedidos360.dynamic.demo","exchange":"pedidos360.dynamic.events","routingKey":"demo.created"}`. Crea primero la cola y el exchange. La clave vacía es válida para exchanges `fanout`.


### Despliegue en AWS: dos EC2

En el despliegue actual, `pedidos-service` y `productos-service` corren en la EC2 `t3.micro`. Los dos nodos RabbitMQ corren en Docker Compose sobre otra EC2 `t3.small` dedicada. El clúster permite mostrar la integración de los dos nodos, pero ambos dependen de la misma instancia; las colas son durables, no replicadas, y la aplicación se conecta al nodo 1. Esto no ofrece alta disponibilidad frente a una caída de esa EC2.

1. Copia el repo actualizado a la EC2 del broker y verifica que tenga Docker Engine y el plugin Compose.
2. Crea un `.env` en el directorio del `docker-compose.yml`; genera una cookie Erlang larga y una contraseña propia. No subas `.env` a Git ni uses los valores locales en la nube.
3. Ejecuta `docker compose up -d` y verifica `docker compose ps` y `docker exec pedidos360-rabbit1 rabbitmqctl cluster_status`. Deben aparecer `rabbit@rabbit1` y `rabbit@rabbit2` como nodos del clúster.
4. En el Security Group del broker permite TCP `5672` solo desde el Security Group del backend. Para SSH usa la misma regla restringida que te permite conectar por EC2 Instance Connect. No abras `5673`, `15672`, `15673`, `4369` ni `25672` a Internet. Los nodos se comunican por la red Docker interna. Para ver la consola remotamente, crea un túnel SSH: `ssh -L 15672:127.0.0.1:15672 <usuario-ec2>@<ip-publica-broker>` y abre `http://localhost:15672`.
5. En el `.env` del broker configura `RABBITMQ_BIND_ADDRESS=<ip-privada-broker>`; en `pedidos-service` de la `t3.micro` configura `RABBITMQ_HOST=<ip-privada-broker>`, `RABBITMQ_PORT=5672`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` y `RABBITMQ_VHOST=/pedidos360`. Activa el perfil de producción de pedidos y configura MySQL con sus variables existentes. Productos no necesita conexión RabbitMQ.
6. Compila el JAR de pedidos localmente con Java 21 y Maven, cópialo a la `t3.micro` y reinicia solo `pedidos.service`. El `rabbit-admin-service` existe en el repositorio, pero su despliegue en AWS no está confirmado. Verifica memoria antes de ejecutarlo: la `t3.micro` ya aloja dos procesos Java.
7. Agrega al API Gateway una ruta hacia `8083` para los endpoints `/admin/*` solo si necesitas administrar colas desde fuera de la instancia; conserva validación JWT y rol `Admin`. No expongas `8083` directamente a Internet. El frontend GitHub Pages y el registro SPA de Entra ID no requieren cambios para el flujo actual de pedidos/productos.
8. Verifica `Overview` por el túnel SSH, confirma los dos nodos conectados y las colas/exchanges/DLQ, y crea un pedido desde el frontend. Revisa los logs de `pedidos-service`. Para probar una DLQ, publica un JSON inválido a una cola de trabajo y confirma que llegue a la DLQ correspondiente.
9. Vigila memoria y CPU con `free -h` en ambas instancias y `docker stats` en la `t3.small`.

La mensajería local usa AMQP sin TLS; en AWS el acceso queda limitado por red privada y Security Groups. Para tráfico entre redes o cuentas distintas, configura TLS/certificados en el broker o usa un broker administrado antes de habilitar conexiones externas.
