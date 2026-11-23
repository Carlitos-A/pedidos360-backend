# Pruebas de integración y contrato

## Brokers locales reales

Estas pruebas usan procesos Kafka y RabbitMQ de Docker, no brokers embebidos. Cada
ejecución usa nombres de tópicos, grupos, colas e intercambios únicos. Kafka debe
tener tres brokers activos para crear tópicos con tres particiones y tres réplicas.
Las bases de datos de los servicios en las pruebas son H2; la persistencia se
comprueba después del consumo. Esto no sustituye la prueba de RDS en AWS.

```powershell
docker compose -f docker-compose.kafka.yml up -d --wait
# Configurar primero RABBITMQ_ERLANG_COOKIE, RABBITMQ_DEFAULT_USER y
# RABBITMQ_DEFAULT_PASS en .env (o en variables de entorno).
docker compose -f docker-compose.yml up -d --wait rabbit1

$env:RUN_BROKER_IT = 'true'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092,localhost:9093,localhost:9094'
$env:RABBITMQ_HOST = 'localhost'
$env:RABBITMQ_PORT = '5672' # cambiar si se publicó otro puerto en Compose
$env:RABBITMQ_USERNAME = '<usuario local de RabbitMQ>'
$env:RABBITMQ_PASSWORD = '<clave local de RabbitMQ>'
$env:RABBITMQ_VHOST = '/pedidos360'

mvn -f pedidos-service/pom.xml '-Dtest=RealKafkaPublisherIT,RealRabbitPipelineIT' test
mvn -f report-service/pom.xml -Dtest=RealKafkaPipelineIT test
mvn -f audit-service/pom.xml -Dtest=RealKafkaAuditPipelineIT test
```

Sin `RUN_BROKER_IT=true`, estas clases se omiten en la suite normal. La prueba
del productor verifica outbox de pedidos → Kafka con clave `order:<id>` y ACK.
Reportes verifica Kafka → consumidor → proyección y recibos, duplicado,
reintento sin confirmar offset mientras no existe DLT, metadatos DLT y topología
3×3. Auditoría verifica Kafka → consumidor → tabla, duplicado y DLT. RabbitMQ
verifica outbox → broker → notificación/ticket persistidos, duplicado, reintento
y registro de mensaje rechazado desde la DLQ. Las colas de prueba se eliminan
al terminar.

## Gateway desplegado con JWT reales

Guardar localmente dos **access tokens emitidos para la API** mediante MSAL,
uno con rol `Admin` y otro con rol `Cliente`. No usar ID tokens. Los archivos
locales `.tokens/` están excluidos de Git. No pegar tokens en argumentos de
línea de comandos, tickets ni logs. El script solo imprime resultados HTTP.

```powershell
python scripts/verify-gateway-contract.py `
  --base-url 'https://<api-id>.execute-api.<region>.amazonaws.com' `
  --admin-token-file .tokens/admin.txt `
  --client-token-file .tokens/cliente.txt `
  --spa-origin 'https://<origen-real-de-angular>'
```

El verificador consulta las mismas rutas `/api/*` que Angular: pedidos,
productos, cuatro reportes, auditoría y health. Comprueba 401 sin token y con
firma alterada, 403 del Cliente en rutas Admin y creación, aislamiento de
`oid`+`tid` en **todas** las páginas de pedidos, conteo filtrado y acceso
denegado al detalle de un pedido ajeno. Comprueba CORS para PATCH desde el
origen SPA indicado. El resultado de detalle ajeno figura `SKIP` si la primera
página Admin no contiene un pedido de otro propietario; preparar uno con un
Admin antes de aceptar la evaluación de aislamiento. Un resultado local no
acredita la red, el authorizer ni RDS del despliegue AWS: volver a ejecutar
este contrato contra la URL pública definitiva con ambos usuarios reales.
