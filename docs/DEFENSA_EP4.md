# Pedidos360: guion de defensa EP4

Duración objetivo: 7 a 8 minutos. Esta guía acompaña la pauta EP4 y el encargo EP3. La demostración se hace con el frontend, AWS, Azure y RabbitMQ abiertos en pestañas distintas.

## La idea en palabras simples

Un **pedido** es el dato que queda guardado en MySQL. Un **mensaje** es una copia pequeña de la información necesaria para que otro trabajo se haga después. `pedidos-service` es el **productor**: guarda el pedido y envía tres mensajes. RabbitMQ recibe cada mensaje en un **exchange** (clasificador), usa un **binding** (regla de ruta) para depositarlo en una **cola** (buzón), y un `@RabbitListener` es el **consumidor** que lo lee. Si lo procesa, envía **ACK** (confirmación). Si el contenido es inválido, envía **NACK** sin reencolar; RabbitMQ lo mueve mediante el dead-letter exchange a la **DLQ** (buzón de fallos).

Los consumidores actuales representan notificación, ticket y auditoría mediante logs. No envían correos ni generan PDFs reales. Las colas son durables, pero no están replicadas entre los dos nodos.

```text
Angular + login Entra ID
  -> JWT -> AWS API Gateway / BFF -> pedidos-service -> MySQL
                                      |
                                      +-> direct, orders.created         -> notificación -> ACK o DLQ
                                      +-> topic, orders.created.ticket  -> ticket       -> ACK o DLQ
                                      +-> topic, orders.created.audit   -> auditoría    -> ACK o DLQ
```

## Preparación antes de la presentación

1. Abre el frontend publicado en GitHub Pages y entra con un usuario de rol `Admin`. Ten abierta también la página **Mi perfil**, que muestra roles y scopes del access token.
2. Antes de ensayar, abre AWS desde el portal del laboratorio universitario y comprueba que `GET /pedidos` y `GET /productos` pasan por el Gateway. El 20-10-2026 ambos respondían **503** desde la URL pública, aunque los dos servicios respondían **401** correctamente en la EC2. El 503 es un bloqueo real de la demostración integrada: no lo presentes como rechazo de JWT. Revisa la sección «Si el Gateway responde 503».
3. Deja abiertas la configuración real de rutas/integraciones/autorización del API Gateway, y la vista de las dos EC2. En Azure, deja abiertos el tenant de Entra ID, los usuarios y los registros de aplicaciones. No afirmes que el Gateway valida el JWT hasta verificar si lo hace allí, en el BFF o en los microservicios.
4. En la EC2 `t3.micro` comprueba `sudo systemctl is-active pedidos.service productos.service`. El JAR de pedidos con logs `PUBLICACION`, `ACK` y `NACK` ya se desplegó y se verificó el 20-10-2026.
5. En la EC2 `t3.small`, desde `/home/ec2-user/pedidos360-backend`, muestra `sudo docker compose ps` y luego los comandos de RabbitMQ de la sección siguiente. Comprueba que las tres colas principales tengan un consumidor.
6. Publica el frontend nuevo en GitHub Pages después de guardar tus cambios en Git. El panel de flujo existe en el código local, pero **todavía no estaba publicado** el 20-10-2026. Comprueba que aparece al crear un pedido de prueba.
7. Antes de la prueba de error, anota cuántos mensajes tiene `pedidos360.orders.notification.dlq`. Así podrás mostrar el incremento exacto sin borrar mensajes existentes.

## Guion en vivo

| Tiempo | Qué mostrar | Qué decir |
|---|---|---|
| 0:00–1:00 | Diagrama anterior y dos EC2 en AWS. | «El frontend está en GitHub Pages. API Gateway recibe las solicitudes; `pedidos-service` y `productos-service` corren en una t3.micro. RabbitMQ corre en una t3.small dedicada». |
| 1:00–2:00 | Tenant, usuarios y app registrations en Entra ID; login en el frontend; Mi perfil. | «Microsoft Entra ID autentica al usuario mediante OAuth 2.0/OpenID Connect y entrega un access token JWT con scope y rol. El frontend lo adjunta al llamar al API». |
| 2:00–3:00 | API Gateway: rutas, integraciones y punto de validación JWT. En otra terminal, una llamada sin token. | «Esta es la ruta pública. La solicitud sin token se rechaza aquí o en el servicio, según la configuración que estamos mostrando. Con el JWT del frontend la solicitud llega al backend». |
| 3:00–4:00 | En Pedidos, crea un pedido con cliente `DEMO-EP4` y un detalle reconocible; mira el ID que aparece. En DevTools/Network, muestra URL del Gateway y respuesta 201. | «El backend guarda este pedido en MySQL y envía tres mensajes identificados por el mismo número de pedido. La respuesta HTTP es independiente del trabajo posterior de los consumidores». |
| 4:00–5:30 | Logs de `pedidos.service`; filtra por el ID. | «Aquí aparecen tres publicaciones y tres ACK: notificación por exchange direct, ticket y auditoría por exchange topic. Cada consumidor confirma su propio mensaje». |
| 5:30–6:30 | `docker compose ps`, estado del clúster, exchanges, bindings y seis colas. | «Hay dos nodos RabbitMQ en un clúster sobre la t3.small; existen tres colas de trabajo y una DLQ para cada una. El direct usa una clave exacta y el topic permite patrones». |
| 6:30–7:30 | Publica un JSON inválido desde la consola RabbitMQ en el exchange direct; muestra NACK y DLQ. | «Si el consumidor no puede interpretar el mensaje, lo rechaza sin bucle de reintento y RabbitMQ lo mueve a la cola de errores». |
| 7:30–8:00 | Vuelve al frontend. | «El pedido sigue disponible; el fallo de una tarea asíncrona queda separado y visible para revisión». |

La pauta pide entre 5 y 10 minutos. Si hay poco tiempo, no leas los nombres completos de las seis colas: señala el patrón cola de trabajo + DLQ y muestra una fila de cada tipo.

## Comandos de solo lectura para las dos EC2

En la EC2 `t3.micro` (servicios Java):

```bash
sudo systemctl is-active pedidos.service productos.service
sudo journalctl -u pedidos.service --since '15 minutes ago' --no-pager | grep '\[RabbitMQ\]'
```

En la EC2 `t3.small` (broker):

```bash
cd /home/ec2-user/pedidos360-backend
sudo docker compose ps
sudo docker exec pedidos360-rabbit1 rabbitmqctl -n rabbit@rabbit1 cluster_status
sudo docker exec pedidos360-rabbit1 rabbitmqctl -p /pedidos360 list_exchanges name type durable
sudo docker exec pedidos360-rabbit1 rabbitmqctl -p /pedidos360 list_bindings source_name destination_name routing_key
sudo docker exec pedidos360-rabbit1 rabbitmqctl -p /pedidos360 list_queues name messages_ready consumers
```

Es normal que las colas de trabajo indiquen cero mensajes tras crear el pedido: los consumidores leen y confirman muy rápido. Para probar el procesamiento usa el ID en los logs, y para probar una DLQ usa el escenario de error siguiente.

## Cómo leer el flujo sin perderte

Piensa en una oficina: el frontend entrega una solicitud, MySQL archiva el pedido, el productor deja tres sobres, los exchanges son los clasificadores, las colas son los buzones y cada consumidor es una persona que realiza una tarea. El ACK es su firma de «terminado»; la DLQ es la bandeja donde quedan los sobres que no pudo entender.

| Sobre/tarea | Clasificador | Clave escrita en el sobre | Buzón de trabajo | Buzón de error |
|---|---|---|---|---|
| Notificación | `pedidos360.orders.direct` | `orders.created` | `pedidos360.orders.notification` | `pedidos360.orders.notification.dlq` |
| Ticket | `pedidos360.orders.topic` | `orders.created.ticket` | `pedidos360.orders.ticket` | `pedidos360.orders.ticket.dlq` |
| Auditoría | `pedidos360.orders.topic` | `orders.created.audit` | `pedidos360.orders.audit` | `pedidos360.orders.audit.dlq` |

El `direct` compara una clave exacta; el `topic` admite patrones aunque aquí los bindings se configuraron con claves completas. Un pedido válido debe producir **tres líneas `PUBLICACION` y tres líneas `ACK`**, todas con el mismo `pedido=<ID>` y tareas diferentes. Las líneas pueden aparecer intercaladas, porque los consumidores trabajan de manera asíncrona. `PUBLICACION` confirma que el código invocó el envío, no por sí sola que RabbitMQ confirmó la entrega; los `ACK` y la presencia de los bindings son la evidencia del recorrido consumido. Si una cola muestra `messages_ready=0` después, eso es compatible con que todos los mensajes fueron procesados, no con que nunca existieron.

Para seguir tu pedido sustituye `42` por el ID que muestre el frontend:

```bash
sudo journalctl -u pedidos.service --since '15 minutes ago' --no-pager | grep 'pedido=42'
```

Para el caso de fallo, no crees un pedido inválido desde el frontend. Publica **un mensaje de prueba** directamente en RabbitMQ y busca una línea `NACK`, luego el aumento de una DLQ. Así separas la validación del formulario/HTTP de la gestión de errores del consumidor.

## Prueba controlada de DLQ

1. Abre la consola de RabbitMQ mediante un túnel SSH al puerto `15672` de la `t3.small`. Usa el vhost `/pedidos360`.
2. En **Exchanges**, abre `pedidos360.orders.direct` y su sección **Publish message**.
3. Escribe routing key `orders.created` y payload `{"orderId":null,"customer":""}`. Publica **una sola vez**. Esta ruta llega solo a la cola de notificación; no crea ni modifica un pedido en MySQL.
4. En los logs del servicio de pedidos busca `NACK tarea=notificación`. Luego vuelve a listar colas: `pedidos360.orders.notification.dlq` debe tener un mensaje más que antes. En la consola puedes inspeccionar el mensaje y la razón `rejected` en `x-death`.
5. Conserva ese mensaje de prueba en la DLQ durante la defensa. No purgues una cola que contenga mensajes que no sean tuyos.

Para mostrar el rechazo de una llamada sin JWT a través de la URL pública, usa una terminal de Windows **después de reparar el 503**:

```powershell
curl.exe -i 'https://4r80bd3omi.execute-api.us-east-1.amazonaws.com/pedidos'
```

Debe aparecer una respuesta de rechazo (por ejemplo 401 o 403, según dónde se valida). Repite con `-H 'Authorization: Bearer invalido'` para un token inválido. Después muestra la llamada válida desde el navegador. En DevTools se puede enseñar que existe la cabecera `Authorization`; evita proyectar el valor completo del token. Un **503 no demuestra seguridad JWT**: indica que el recorrido público todavía no está sano.

## Si el Gateway responde 503

1. Entra a AWS desde el portal del laboratorio estudiantil; no uses la pantalla genérica de root/IAM. En `us-east-1`, abre API Gateway y localiza el API cuya URL empieza por `4r80bd3omi`.
2. Identifica si es HTTP API o REST API, la etapa publicada y las rutas `/pedidos` y `/productos`. Abre la integración de cada ruta y anota **tipo y destino** (Lambda/BFF, URL HTTP pública o VPC Link). No cambies la IP a ciegas: primero determina dónde se corta el recorrido.
3. Si el destino es una URL de la EC2, verifica host/IP, puerto y Security Group; si es una Lambda/BFF, verifica su estado y logs; si usa VPC Link, comprueba que está disponible y que el destino asociado responde. Revisa los logs de acceso/ejecución de API Gateway para la hora y `requestId` del 503. Si faltan, habilítalos para el escenario de laboratorio sin registrar el valor completo de JWT.
4. Repite `curl.exe -i` sin token y luego el flujo válido desde el frontend. Solo considera reparado el recorrido cuando el rechazo sea 401/403 **y** una petición con sesión válida devuelva datos o cree un pedido. Después revisa logs `PUBLICACION`/`ACK` y colas.

La verificación del 20-10-2026 mostró 503 para ambas rutas públicas, mientras `localhost:8081/pedidos` y `localhost:8082/productos` devolvían 401 sin token. Eso apunta a un problema **antes o en la integración pública**, pero no prueba cuál componente concreto falla. Si no se repara, puedes enseñar Java y RabbitMQ por separado, explicando honestamente que la integración frontend–Gateway no quedó verificada; no equivale al recorrido completo exigido.

## Qué subir a GitHub

En el repositorio backend incluye los cambios de logs y la API de bindings; en el frontend incluye el panel que muestra el ID y rutas después de crear un pedido. Haz tus commits con tus propias palabras. Un `git push` a `main` del frontend **no actualiza por sí solo** la rama `gh-pages` usada por el sitio actual: compila con `npm run build -- --base-href /pedidos360-frontend/`, publica el contenido de `dist/pedidos360-front/browser` en la raíz de `gh-pages` con el método de despliegue que uses en tu curso, y comprueba el sitio público. No subas `.env`, credenciales, claves privadas ni JWT.

## Cómo explicar cada pieza si te preguntan

- **¿Por qué usar RabbitMQ?** El pedido se guarda una vez, y las tareas posteriores se distribuyen por mensajes. Una tarea de auditoría no necesita estar mezclada con la pantalla de pedidos.
- **¿Direct y topic?** Direct compara la clave exacta `orders.created`; topic usa claves con segmentos, como `orders.created.ticket` y `orders.created.audit`.
- **¿Por qué seis colas si piden tres?** Son tres colas de trabajo, cada una con su DLQ correspondiente.
- **¿Qué hace ACK?** Indica que el consumidor terminó y RabbitMQ puede quitar ese mensaje de la cola de trabajo.
- **¿Qué hace NACK?** Rechaza el mensaje. En este proyecto se usa `requeue=false`, por lo que la configuración de dead-letter lo envía a la DLQ. No hay reintento automático implementado.
- **¿Dos nodos significa alta disponibilidad?** No aquí: los dos contenedores dependen de la misma t3.small y las colas no se replican. Sí permiten mostrar un clúster de dos nodos.
- **¿Se enviaron correos o PDFs?** No. Los consumidores académicos registran la ejecución en logs; ahí se conectarían proveedores reales más adelante.
- **¿Para qué sirve `rabbit-admin-service`?** Expone una API protegida para crear y eliminar colas, exchanges y bindings dinámicos. No debe confundirse con las tres colas fijas del flujo de pedidos. Su despliegue cloud todavía debe verificarse por separado.

## Evidencia que debes tener lista

Frontend publicado, login, página Mi perfil, URL y respuesta del Gateway, rechazo sin JWT, ambas EC2 activas, `docker compose ps`, `cluster_status` con dos nodos, exchanges direct/topic, tres colas + tres DLQ, ID del pedido en el frontend y logs `PUBLICACION`/`ACK`, y un `NACK` con incremento visible de la DLQ.
