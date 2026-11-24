# Demostración visual de RabbitMQ

El panel **Evidencias** de Angular llama a `POST /api/orders/demo/rabbit` con
`{"action":"reject"}` o `{"action":"clear"}`. Solo un JWT con rol `Admin` y,
si está activada la política, scope `pedidos.write` puede usarlo. El cliente
nunca envía el payload ni credenciales de RabbitMQ.

`reject` publica el JSON inválido fijo `{invalid` en
`pedidos360.demo.direct` con routing key `demo.invalid`. Un consumidor que usa
la misma validación de la cola de notificaciones hace NACK sin reencolar.
RabbitMQ lo mueve a `pedidos360.demo.notification.dlq`. Esta cola no tiene
monitor, por lo que el mensaje permanece visible en Overview y Queues and
Streams. `clear` purga **solo esa DLQ de demostración** para repetir el ensayo.
Las seis colas operativas y sus monitores no se modifican.

El módulo se activa con `RABBIT_DEMO_ENABLED=true`. El Compose de Apps lo
activa por defecto para el laboratorio; fuera de Compose está desactivado por
defecto. Antes de usar el botón en AWS, reconstruir `pedidos-service` y agregar
en API Gateway una ruta exacta `POST /api/orders/demo/rabbit`, con el JWT
authorizer y `pedidos.write`, asociada a la integración del ALB interno. La
regla existente del ALB `/api/orders/*` lo dirige a `pedidos-service`.

Recorrido: abrir RabbitMQ Management mediante túnel SSH, entrar a Evidencias
como Admin, pulsar **Enviar mensaje fallido**, esperar unos segundos y
actualizar el panel RabbitMQ. Overview debe mostrar un mensaje en Ready y
`pedidos360.demo.notification.dlq` debe mostrarlo en su fila. Pulsar **Limpiar
cola de prueba** y actualizar RabbitMQ para volver a cero. Un evento inválido
se rechaza antes del bucle de reintentos: esta prueba demuestra NACK y DLQ,
no el reintento de un fallo transitorio.
