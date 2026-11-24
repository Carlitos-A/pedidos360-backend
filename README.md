# Pedidos360 — Backend

Microservicios Spring Boot del sistema Pedidos360 (EP1 · DSY1107).

Para un despliegue nuevo de las seis APIs y las cuatro bases, seguir
[`docs/aws-code-deploy.md`](docs/aws-code-deploy.md). Ese flujo usa Flyway
en producción y `ddl-auto: validate`. Por la restricción IAM del laboratorio,
MySQL 8.4 corre en la EC2 Apps mediante Compose, con volumen persistente y
usuarios separados; no se crea RDS. Los SQL fechados de `db/migrations`
son únicamente para actualizar instalaciones históricas.

## Perfiles para el nuevo despliegue

`prod` configura MySQL, Entra y CORS con variables obligatorias; no selecciona
el transporte Kafka. Activar **exactamente uno** de los perfiles `msk` o
`kafka-compose` en los cinco servicios que usan Kafka (`pedidos`, `productos`,
`audit`, `report`, `kafka-admin`). `msk` usa `SASL_SSL` y `AWS_MSK_IAM`;
`kafka-compose` usa `PLAINTEXT` y tres brokers. Para desarrollo local basta
`kafka-compose`; para MySQL junto al clúster Compose usar `prod,kafka-compose`;
para Amazon MSK usar `prod,msk`. En `prod`, definir `KAFKA_BOOTSTRAP_SERVERS`.

| Variable de backend | Uso |
|---|---|
| `ENTRA_TENANT_ID` | Issuer JWT y tenant permitido para clientes registrados |
| `ENTRA_API_AUDIENCE` | Valor exacto del claim `aud` del access token emitido para la API (por ejemplo, el GUID de la App Registration). Es distinto del scope solicitado por la SPA, que puede usar `api://<id>/pedidos.read`. |
| `FRONT_URL` | Origen de la SPA para CORS, sin ruta de repositorio |
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers anunciados y alcanzables desde el proceso Java |
| `INTERNAL_SERVICE_KEY`, `MYSQL_ROOT_PASSWORD`, contraseñas `*_DB_PASSWORD`, `RABBITMQ_PASSWORD` | Secretos suministrados fuera de Git |

Los valores de Entra antiguos permanecen como valores de desarrollo en
`application.yml`; los perfiles `prod` requieren los identificadores del nuevo
tenant y API. `REQUIRE_WRITE_SCOPE` vale `true` por defecto en producción para
pedidos y productos: POST/PUT/PATCH exigen rol `Admin` **y** `pedidos.write` en
el claim `scp` del access token. GET conserva `pedidos.read` en la configuración
del frontend. Antes de habilitar escrituras en el gateway, exponer y conceder
`pedidos.write` en Entra, comprobar un token real con `aud`, `roles` y `scp`, y
probar 403 para Admin sin scope y Cliente con scope. Las pruebas de seguridad
automatizadas cubren esas dos negativas y el Admin con scope.

El clúster de evaluación de [Docker Compose](docker-compose.kafka.yml) contiene
tres ZooKeeper, tres brokers con réplicas e ISR 2 y Kafka-UI. Los listeners internos anuncian
`broker1:29092,broker2:29092,broker3:29092`; usar esos nombres desde otros
contenedores de `pedidos360-messaging`. Para procesos Java fuera de Docker en el mismo host,
usar `localhost:9092,localhost:9093,localhost:9094` con el perfil
`kafka-compose`. Si el host de brokers es otro equipo privado, configurar
`KAFKA_BIND_ADDRESS` y `KAFKA_ADVERTISED_HOST` con su IP privada y restringir
9092–9094 al Security Group de los servicios. No publicar esos listeners
PLAINTEXT en Internet.

```powershell
docker compose -f docker-compose.kafka.yml up -d --wait
docker compose -f docker-compose.kafka.yml ps
docker compose -f docker-compose.kafka.yml exec -e KAFKA_BOOTSTRAP_SERVERS=broker1:29092,broker2:29092,broker3:29092 -e KAFKA_TOPICS_BIN=kafka-topics -e KAFKA_CONFIGS_BIN=kafka-configs broker1 bash /opt/pedidos360/kafka/apply-topics.sh
```

Compose usa Confluent Platform 7.9 porque conserva ZooKeeper; es una topología
de evaluación en un solo host. Para un despliegue nuevo administrado usar MSK.

## Contratos OpenAPI

Cada servicio genera su contrato OpenAPI 3 en `GET /v3/api-docs` y una interfaz
Swagger en `/swagger-ui.html`. El contrato incluye modelos de solicitud y
respuesta, Bearer JWT y los códigos de autorización `401`/`403`. El código
`409` se documenta en las operaciones que pueden producir un conflicto de
inventario, versión, transición, stock o nombre de tópico.
Las anotaciones de cada controlador explican restricciones de roles, propiedad,
filtros y cambios de estado. Para probar las rutas protegidas en Swagger UI,
usar **Authorize** con un access token emitido para esta API.

| Servicio | Puerto local | Rutas documentadas |
|---|---:|---|
| `pedidos-service` | 8081 | `/api/**`, incluidos pedidos, clientes, avisos, comprobantes, health y rutas heredadas marcadas como obsoletas |
| `productos-service` | 8082 | `/api/products` y `/api/products/**` |
| `rabbit-admin-service` | 8083 | `/admin/**` |
| `audit-service` | 8084 | `/api/audit/**` |
| `report-service` | 8085 | `/api/reports/**` |
| `kafka-admin-service` | 8086 | `/internal/kafka/**` |

En `kafka-admin-service`, Swagger también declara `X-Internal-Key`: la llamada
requiere esa clave **y** el JWT Admin. Los contratos de los administradores se
consultan dentro de la red privada. En AWS, mantener `/v3/api-docs` y
`/swagger-ui/**` fuera de las rutas públicas del API Gateway; la SPA usa las
rutas `/api/*` del gateway, nunca los puertos de los servicios.

## Avisos y comprobantes opcionales

`pedidos-service` procesa los eventos RabbitMQ v2 de creación en dos consumidores
independientes. El de notificaciones guarda un aviso visible para el Cliente en
el Dashboard; el de tickets guarda un comprobante de creación con número
`P360-{orderId}`, fecha, ítems, precios y total CLP del snapshot confirmado por
el servidor. Es un comprobante interno, no una boleta tributaria ni un email.
La creación del pedido no espera a estos consumidores: el outbox publica de
forma asíncrona y RabbitMQ conserva mensajes mientras no se consuman.

`GET /api/notifications?page=0&pageSize=20` entrega avisos paginados. Cliente
solo ve avisos cuyo destinatario **y propietario actual del pedido** coinciden
con `oid` y `tid` de su JWT; Admin puede inspeccionar todos. `GET
/api/orders/{id}/ticket` entrega el comprobante a Admin o al Cliente que sigue
siendo propietario y destinatario original. La UI ofrece actualización manual
de avisos y botones para consultar o descargar el comprobante en texto; un 404 indica que el ticket
todavía no está disponible o no pertenece al usuario. El gateway de AWS debe
enrutar `/api/notifications` a `pedidos-service` junto a `/api/orders/*`.

Cada consumidor deduplica por `eventId` con restricción única en base de datos;
el ticket también es único por pedido. Confirma RabbitMQ solo después de guardar.
Un evento histórico v1 o incompleto va a la DLQ correspondiente, sin inventar
dueño, ítems ni precio. Un fallo transitorio se intenta 3 veces con 500 ms de
pausa y luego va a DLQ; ajustar con `OPTIONAL_WORK_RETRY_ATTEMPTS` y
`OPTIONAL_WORK_RETRY_DELAY_MS`. El lector de DLQ documentado abajo registra
esos rechazos. Antes de activar `OPTIONAL_WORK_ENABLED=true` en producción,
aplicar [`2026-11-21-optional-order-work.sql`](pedidos-service/db/migrations/2026-11-21-optional-order-work.sql)
en `pedidosdb`. El valor inicial es `false` para permitir el corte de base y
gateway. Los pedidos anteriores requieren una reconstrucción verificada; no se
generan comprobantes históricos a partir de campos incompletos.

## Recuperación y registro de rechazos

Los consumidores de auditoría y reportes envían eventos inválidos a sus DLT
respectivos conservando el payload original. Cada registro DLT incluye los
encabezados `pedidos360.dlt.source-topic`, `source-partition`, `source-offset`,
`cause` y `rejected-at` (UTC ISO 8601). El offset de origen se confirma **solo**
después de que Kafka confirme la escritura en DLT. Un fallo de base de datos o
de la publicación DLT reintenta el mismo offset cada segundo, sin límite y sin
saltarlo; `AUDIT_RETRY_DELAY_MS` y `REPORT_RETRY_DELAY_MS` ajustan la pausa.
Por ello una partición queda detenida mientras persista el fallo y debe alertarse
operativamente por lag del grupo y errores del servicio.

`audit-service` puede consumir ambos DLT con el grupo independiente
`pedidos360-dlt-monitor` (`AUDIT_DLT_MONITOR_ENABLED=true`). Guarda tópico,
partición y offset DLT, origen, causa, fecha y SHA-256 del payload en `auditdb`,
sin copiar su contenido a la tabla. La clave de posición evita registros
duplicados tras redelivery; si falla la base no confirma el offset. Aplicar
[`2026-11-21-rejected-kafka.sql`](audit-service/db/migrations/2026-11-21-rejected-kafka.sql)
antes de activarlo en producción y conceder lectura de ambas DLT al rol MSK.
`GET /internal/rejections/kafka?page=0&pageSize=20` permite inspección paginada
solo a Admin; no crearle una ruta pública en API Gateway.

`pedidos-service` puede consumir las tres DLQ RabbitMQ existentes con
`RABBIT_DLQ_MONITOR_ENABLED=true`. Confirma cada mensaje después de registrar
cola, origen, motivo `x-death`, `messageId`, fecha y SHA-256 en `pedidosdb`.
Si falla el registro, reencola en la misma DLQ después de una pausa de un
segundo. El lector **vacía** las DLQ al registrar; el contenido original no se
guarda en la tabla ni se reenvía automáticamente. Aplicar
[`2026-11-21-rejected-rabbit.sql`](pedidos-service/db/migrations/2026-11-21-rejected-rabbit.sql)
antes de activarlo en producción. `GET /internal/rejections/rabbit?page=0&pageSize=20`
es exclusivo de Admin y debe quedar en la red interna. Para recuperar el cuerpo
de un rechazo Kafka se usa `kafka/inspect-dlt.sh TOPICO PARTICION OFFSET` desde
un host privado con Kafka CLI y permisos de lectura; su salida contiene el
payload original y debe tratarse como dato sensible. Los registros persistidos
sirven para diagnóstico y trazabilidad, no para replay automático.

## Topología Kafka reproducible

### Administración interna

`kafka-admin-service` (puerto 8086) usa Kafka AdminClient y ofrece:

| Método | Ruta | Acción |
| --- | --- | --- |
| GET | `/internal/kafka/topics` | Lista nombres, particiones y réplicas |
| GET | `/internal/kafka/topics/{name}` | Detalle, réplicas en sincronía y configuraciones no sensibles |
| POST | `/internal/kafka/topics` | Crea un tópico con particiones, réplicas, retención y limpieza |
| PATCH | `/internal/kafka/topics/{name}` | Aumenta particiones o cambia retención/limpieza |
| DELETE | `/internal/kafka/topics/{name}` | Elimina un tópico solo con `KAFKA_ADMIN_ALLOW_DELETE=true` |
| GET | `/internal/kafka/groups` | Lista grupos y estado |
| GET | `/internal/kafka/groups/{groupId}` | Muestra miembros, asignaciones y offsets confirmados |

Cada ruta exige simultáneamente un JWT válido para la API con rol `Admin` y
`X-Internal-Key` igual a `INTERNAL_SERVICE_KEY` no vacío. Mantener 8086 en red
privada; **no crear rutas públicas en API Gateway** hacia `/internal/kafka/*`.
Para AWS MSK, el perfil `prod` usa IAM y necesita permisos Kafka de descripción,
creación y modificación de tópicos y lectura de grupos; la eliminación requiere
permiso adicional y el flag explícito. El servicio no reinicia offsets ni altera
asignaciones de grupos activos. El manifiesto siguiente sigue siendo la fuente
reproducible de la topología; la API permite inspección y cambios controlados.
La imagen se construye con `docker build -t pedidos360-kafka-admin ./kafka-admin-service`;
el Dockerfile no publica puertos del host. En AWS, desplegarlo sin IP pública ni
listener público, con grupo de seguridad que permita 8086 solo desde la red de
operación y acceso saliente a MSK y a Microsoft Entra para validar JWT. Inyectar
`INTERNAL_SERVICE_KEY` desde Secrets Manager y no compartirlo con Angular.

El manifiesto [`kafka/topics.csv`](kafka/topics.csv) declara los **dos tópicos
principales** y los tópicos auxiliares. Se aplica con
[`kafka/apply-topics.sh`](kafka/apply-topics.sh) desde una máquina con clientes
Kafka y acceso privado a los brokers:

```bash
KAFKA_BOOTSTRAP_SERVERS=broker1:9092,broker2:9092,broker3:9092 bash kafka/apply-topics.sh
```

Para un clúster con autenticación se pasa `KAFKA_COMMAND_CONFIG` con la ruta a
las propiedades del cliente Kafka. El script crea los tópicos ausentes,
actualiza retención y política de limpieza y **falla** si un tópico existente
tiene otro número de particiones o réplicas; no redistribuye uno activo.
Requiere tres brokers disponibles porque todos los tópicos declaran factor de
réplica 3. Las credenciales y las direcciones reales se aportan en el entorno,
no en el repositorio.
Para inspeccionar desde el mismo host privado se usa
`kafka/inspect-kafka.sh topics`, `topic NOMBRE`, `groups` o `group ID` con las
mismas variables de conexión. Los scripts no publican endpoints.

| Tópico | Propósito | Particiones | Réplicas | Retención | Grupo lector |
| --- | --- | ---: | ---: | ---: | --- |
| `pedidos360.orders.events.v1` | Snapshots de negocio para ventas, estados y productos | 3 | 3 | 7 días | `pedidos360-report` |
| `pedidos360.audit.v1` | Eventos mínimos de trazabilidad para el timeline | 3 | 3 | 30 días | `pedidos360-audit` |
| `pedidos360.login.v1` | Reservado para una futura fuente confiable de logins | 3 | 3 | 7 días | `pedidos360-audit` |
| `pedidos360.report.dlt.v1` | Eventos de reporte inválidos | 3 | 3 | 14 días | inspección manual |
| `pedidos360.audit.dlt.v1` | Eventos de auditoría inválidos | 3 | 3 | 14 días | inspección manual |

Los dos grupos son independientes y configurables mediante
`REPORT_CONSUMER_GROUP` y `AUDIT_CONSUMER_GROUP`. Pedidos publica ambos tipos
con clave `order:<id>`, de modo que los eventos del mismo pedido van a una
partición estable dentro de cada tópico. Productos publica auditoría con clave
`product:<id>`. Todos los tópicos usan `cleanup.policy=delete`: compactar por
ID de entidad descartaría eventos anteriores necesarios para reconstruir la
línea de tiempo. La persistencia de `auditdb` y `reportdb` tiene su propio ciclo
de vida, distinto de la retención Kafka. Los consumidores no crean tópicos al
suscribirse; ejecutar el manifiesto antes de habilitar los flags Kafka.

## Reportería proyectada desde Kafka

`report-service` (puerto 8085) consume `pedidos360.orders.events.v1` con su propio
grupo y base `reportdb`. `pedidos-service` escribe un snapshot de negocio en el
outbox transaccional al confirmar la creación, editar ítems o datos y cambiar el
estado. Este evento tiene `schemaVersion: 1`, `eventId`, ID y revisión del pedido,
estado, total CLP, fechas UTC e ítems (`productId`, nombre, cantidad y precio
unitario). Los importes e ítems provienen de la reserva confirmada por el
servidor. No contiene datos del propietario, detalle ni actor. El evento de
auditoría sigue siendo separado y no cambia de contrato.

La proyección guarda el último snapshot por pedido y registra cada `eventId` en
la misma transacción. Una revisión antigua o repetida no revierte el estado.
Eventos inválidos se envían a `pedidos360.report.dlt.v1` antes de confirmar su
offset; errores de almacenamiento o DLT se reintentan. Los cuatro endpoints
`/api/reports/{kpis,sales,lead-time,top-products}` conservan el contrato Angular,
pero leen exclusivamente `reportdb`. `activeUsers` sigue en `null` porque no hay
una fuente verificable. `updatedAt` indica la última proyección procesada.

`days` cuenta días de calendario inclusivos en `America/Santiago`. Los conteos
de estado, cancelación y `totalOrders` consideran pedidos creados en el período;
ventas, ticket, lead time y top productos consideran pedidos completados en el
período según la fecha de finalización. Los cancelados no suman ventas. Las
series mensuales usan el mes de finalización en Santiago.

La publicación y el listener permanecen desactivados inicialmente
(`REPORT_KAFKA_ENABLED=false`). Antes de activarlos, aplicar
[`2026-11-21-report-projection.sql`](report-service/db/migrations/2026-11-21-report-projection.sql)
en `reportdb`, configurar `REPORT_KAFKA_TOPIC` y `KAFKA_BOOTSTRAP_SERVERS` y
crear los tópicos. En el gateway nuevo, enrutar `/api/reports/*` a
`report-service:8085`; configurar `REPORT_SERVICE_URL` en pedidos con la URL
interna de `/internal/health` y compartir `INTERNAL_SERVICE_KEY`. El perfil
`prod` usa IAM de AWS MSK. No exponer 8085 ni `/internal/health` al navegador.
Los datos históricos anteriores a la activación no aparecen por arte de magia:
requieren un backfill verificado de snapshots o replay de eventos completos.
La ruta anterior en `pedidos-service` se conserva solo durante el corte.

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
offset. `pedidos360.login.v1` queda reservado: no hay productor de
`USER_LOGIN`, ya que el frontend no puede acreditar ante el servidor un
inicio de sesión exitoso. El consumidor de auditoría conserva la compatibilidad
con ese tipo para una futura fuente confiable.

Variables compartidas: `KAFKA_BOOTSTRAP_SERVERS`, `AUDIT_KAFKA_ENABLED` y
`AUDIT_KAFKA_TOPIC`. `audit-service` acepta `AUDIT_LOGIN_TOPIC`,
`AUDIT_DLT_TOPIC`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` y
`FRONT_URL`. `INTERNAL_SERVICE_KEY` protege `/internal/health`; cuando
`pedidos-service` recibe `AUDIT_SERVICE_URL` con la URL interna de ese endpoint,
`/api/health` consulta al nuevo servicio en vez de la auditoría antigua.
No enrutar `/internal/health` en API Gateway ni exponer el puerto 8084 a Internet.
El perfil `msk` configura `SASL_SSL`/`AWS_MSK_IAM`; las
credenciales provienen del rol de ejecución AWS. Los clientes de pedidos y
productos necesitan escribir en el tópico de auditoría; audit-service necesita
leer los tópicos de auditoría y login con su grupo, y escribir en DLT. El
frontend nunca se conecta a Kafka.

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
frontend. `report-service` sirve
`GET /api/reports/{kpis,sales,lead-time,top-products}`; `pedidos-service`
mantiene esas rutas heredadas durante el corte y sirve `GET /api/health`.
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

`/api/health` está protegido para Admin. `orders` consulta su propia base;
`products`, `report` y `audit` consultan respectivamente sus checks
`/internal/health` con `X-Internal-Key`. Configurar las URLs completas en
`PRODUCTS_HEALTH_URL`, `REPORT_SERVICE_URL` y `AUDIT_SERVICE_URL`, y compartir
un `INTERNAL_SERVICE_KEY` no vacío. Cada check responde
`{"service":"products|report|audit","status":"UP"}` cuando su base está
disponible; los de reportes y auditoría también comprueban metadatos Kafka si
su consumo está activo.
El agregador solo informa `UP` tras recibir HTTP 200 con identidad y estado
correctos. Respuestas 401/403, caídas, timeout o contenido incorrecto son
`DOWN`. Un servicio sin URL o sin clave se omite; Angular muestra «SIN DATOS».
`bff` se omite mientras no exista. Cuando se despliegue uno, configurar
`BFF_HEALTH_URL` hacia su `/internal/health` que responda
`{"service":"bff","status":"UP"}`. Mantener estos checks fuera del gateway
público; únicamente `/api/health` agregado llega al navegador Admin.

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
de paginar y contar. Al abrir una ruta protegida de la SPA, un Cliente se
registra mediante `POST /api/customers/me` con su access token válido. El
servidor toma `oid`, `tid`, rol, nombre y correo **solo del JWT**, y persiste
la cuenta en `registered_customer`. No acepta una identidad enviada en el
cuerpo. Admin busca entre los clientes registrados con
`GET /api/customers?q=...`; `POST /api/orders` y la reasignación vuelven a
comprobar ese registro. Un Cliente debe haber iniciado sesión al menos una
vez para aparecer en la búsqueda. PUT no cambia propietario; Admin usa
`PATCH /api/orders/{id}/owner` y la operación se audita.

En API Gateway, enrutar `POST /api/customers/me` y
`GET /api/customers` a `pedidos-service:8081`. El POST de registro usa el
scope `pedidos.read`, aunque sea POST; es una actualización de la identidad
propia, no una escritura administrativa. El servicio exige el rol `Cliente`
y rechaza un token que también tenga `Admin`. No se necesita una tercera App
Registration ni consentimiento administrativo para el directorio. El registro
refleja el último token observado; sin una consulta de directorio externa no
puede detectar de inmediato una baja o revocación posterior del rol.

La migración histórica es explícita: Admin envía a
`POST /api/orders/ownership-migration` una lista de hasta 100 objetos
`{orderId,customerId,customerTenantId}` construida con una correspondencia
comprobada. El servidor exige que la cuenta esté registrada por un JWT Cliente, y que
el pedido aún no tenga propietario, guarda la correspondencia en
`verified_owner_mapping` con actor y fecha, y registra auditoría. Un error
revierte el lote completo. Nunca se asigna por coincidencia del nombre.

`productos-service` expone `GET/POST /api/products`, `PUT /api/products/{id}`
y `PATCH /api/products/{id}/stock` con cuerpo `{ "delta": -1 }`. El catálogo
es compartido: Admin crea productos con nombre, categoría, precio CLP y stock,
sin asignarlos a una persona. GET `/api/products` devuelve el catálogo a Admin
y Cliente; solo Admin puede escribir. Los pedidos siguen asociados a un Cliente
registrado y pueden reservar productos del catálogo común. Las reservas se
identifican por pedido y propietario para evitar reusar una reserva de otro
Cliente. Las columnas antiguas de propietario de producto se conservan por
compatibilidad con la base existente, pero ya no controlan visibilidad ni
reservas: los productos anteriores también pasan a ser compartidos. El GET
legado `/productos` requiere Admin y el POST legado sigue deshabilitado. Ambos
servicios admiten `PATCH` en CORS.

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

Los perfiles de desarrollo aceptan los identificadores anteriores como valores
por defecto. Para el nuevo despliegue, configurar `ENTRA_TENANT_ID` y
`ENTRA_API_AUDIENCE` en cada servicio con perfil `prod`, y los mismos valores
de API en la compilación del frontend.

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
  - `POST /api/customers/me` → Cliente; las demás escrituras → Admin
- Sin token → 401 · sin permiso → 403.
- CORS habilitado para `http://localhost:4200` en desarrollo; en producción se configura con `FRONT_URL` (el origen de GitHub Pages).

### CORS, rutas y scope de escritura

`FRONT_URL` contiene el origen real de la SPA (sin la ruta del
repositorio). `pedidos-service` y `productos-service` aceptan GET, POST, PUT,
PATCH y OPTIONS con `Authorization` y `Content-Type`. Para varios orígenes usa una lista separada por
comas. El API Gateway necesita su propia configuración CORS. Con un perfil AWS
CLI autorizado, ejecuta [`configure-api-gateway-cors.ps1`](scripts/configure-api-gateway-cors.ps1)
con `-ApiId <nuevo-id> -AllowedOrigins <origen-spa>` para añadir PUT/PATCH y
los dos headers.

Después, revisa las rutas de la nueva HTTP API: POST
`/api/customers/me`, GET `/api/customers`, POST
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
una ruta de escritura permitiría entrar con solo lectura. El perfil `prod`
activa por defecto `REQUIRE_WRITE_SCOPE=true` en pedidos y productos. Así,
cada escritura administrativa exige simultáneamente rol `Admin` y `scp` con
`pedidos.write`. `POST /api/customers/me` exige `Cliente` y debe usar
`pedidos.read` en el gateway.
Angular pide `pedidos.read` para iniciar sesión y GET, y `pedidos.write` solo
para métodos de escritura. Un Cliente podría recibir el scope delegado de la
SPA, pero su rol sigue bloqueando las escrituras.

## Base de datos

MySQL 8.4 en el Compose de la EC2 Apps para el laboratorio y H2 en memoria
para desarrollo, con credenciales por variables de entorno. El puerto 3306 no
se publica; las cuatro APIs usan el nombre interno `mysql`.

## EP4: mensajería asíncrona con RabbitMQ

`pedidos-service` guarda el historial de estados junto con el pedido. Cada evento nuevo incluye estado previo y nuevo, fecha UTC del servidor, nombre, rol, `oid` y `tid` del Admin autenticado. La creación tiene estado previo nulo. Pedidos históricos sin eventos conservan `history: []`; los eventos antiguos no reciben identidad ni estado previo inventados.

La mensajería nueva usa `schemaVersion: 2`: cada evento incluye `eventId`, propietario (`customerId` y `customerTenantId`), ítems, total CLP, actor y fecha UTC. Los consumidores siguen aceptando el `OrderCreatedEvent` antiguo sin versión. El evento se escribe en `order_outbox` en la misma transacción que el pedido y el registro de auditoría. Un trabajo programado lo publica con confirmación de RabbitMQ y reintenta fallos; una caída después de la confirmación puede producir una segunda entrega con el mismo `eventId`, por lo que los consumidores con efectos deben deduplicar por evento y destino. Notificación y ticket guardan efectos opcionales e idempotentes; report-service mantiene su propia proyección desde Kafka.

| Caso | Exchange y tipo | Routing key | Cola | DLQ |
|---|---|---|---|---|
| Notificación | `pedidos360.orders.direct` (direct) | `orders.created` | `pedidos360.orders.notification` | `pedidos360.orders.notification.dlq` |
| Ticket | `pedidos360.orders.topic` (topic) | `orders.created.ticket` | `pedidos360.orders.ticket` | `pedidos360.orders.ticket.dlq` |
| Auditoría | `pedidos360.orders.topic` (topic) | `orders.created.audit` | `pedidos360.orders.audit` | `pedidos360.orders.audit.dlq` |
| Cambio de estado | `pedidos360.orders.topic` (topic) | `orders.status.changed` | `pedidos360.orders.audit` | `pedidos360.orders.audit.dlq` |
| Cancelación | `pedidos360.orders.topic` (topic) | `orders.cancelled` | `pedidos360.orders.audit` | `pedidos360.orders.audit.dlq` |

Nombres y claves se centralizan en `pedidos-service/src/main/resources/application.yml`. Los listeners usan ACK manual; cuando el payload no es procesable, hacen NACK sin requeue y RabbitMQ enruta el mensaje a su DLQ. En un esquema histórico, aplicar los SQL fechados con respaldo y revisión previa. En un esquema nuevo, Flyway crea las tablas y Hibernate las valida. Los SQL fechados son solo para actualizar un esquema histórico; seguir `docs/aws-code-deploy.md`.

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


### Despliegue anterior en AWS: dos EC2 (referencia histórica)

Los pasos siguientes describen la instalación previa de EP4. Para el nuevo
despliegue usar los perfiles y variables de la sección inicial, y crear las
rutas del gateway con los identificadores nuevos.

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
