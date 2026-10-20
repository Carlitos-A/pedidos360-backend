# Pedidos360 — Backend

Microservicios Spring Boot del sistema Pedidos360 (EP1 · DSY1107).

## Microservicios

| Servicio | Puerto | Endpoints |
|---|---|---|
| `pedidos-service` | 8081 | `GET /pedidos`, `POST /pedidos` |
| `productos-service` | 8082 | `GET /productos`, `POST /productos` |
| `rabbit-admin-service` | 8083 | `GET /admin/health`, gestión REST de colas y exchanges |

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
- CORS habilitado para `http://localhost:4200`.

## Base de datos

RDS MySQL en producción y H2 en memoria para desarrollo, con credenciales por variables de entorno.

## EP4: mensajería asíncrona con RabbitMQ

`pedidos-service` publica `OrderCreatedEvent` después de guardar un pedido. Los tres consumidores representan los pasos desacoplados de notificación, generación de ticket y auditoría. En esta entrega dejan constancia del procesamiento en el log; allí se conecta más adelante un proveedor real de correo o documentos.

| Caso | Exchange y tipo | Routing key | Cola | DLQ |
|---|---|---|---|---|
| Notificación | `pedidos360.orders.direct` (direct) | `orders.created` | `pedidos360.orders.notification` | `pedidos360.orders.notification.dlq` |
| Ticket | `pedidos360.orders.topic` (topic) | `orders.created.ticket` | `pedidos360.orders.ticket` | `pedidos360.orders.ticket.dlq` |
| Auditoría | `pedidos360.orders.topic` (topic) | `orders.created.audit` | `pedidos360.orders.audit` | `pedidos360.orders.audit.dlq` |

Nombres y claves se centralizan en `pedidos-service/src/main/resources/application.yml`. Los listeners usan ACK manual; cuando el payload no es procesable, hacen NACK sin requeue y RabbitMQ enruta el mensaje a su DLQ.

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

En otra terminal, desde `rabbit-admin-service`, ejecuta `..\pedidos-service\mvnw.cmd -f pom.xml spring-boot:run`. Al crear un pedido con `POST /pedidos`, los tres listeners lo procesan; se observan sus logs en la terminal del servicio y sus colas en la consola RabbitMQ.

### API de administración

El nuevo `rabbit-admin-service` escucha en el puerto `8083` y requiere un JWT de Entra ID con rol `Admin`; también valida issuer, audience, firma y vigencia como los otros servicios.

- `POST /admin/queues`: crea una cola durable y opcionalmente configura DLX/routing key.
- `DELETE /admin/queues/{name}`: elimina únicamente nombres con prefijo `pedidos360.dynamic.`.
- `POST /admin/exchanges`: crea exchanges `direct`, `topic` o `fanout` bajo el prefijo `pedidos360.dynamic.`.
- `DELETE /admin/exchanges/{name}`: elimina únicamente exchanges dinámicos.
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

### Despliegue en AWS: una sola EC2

En esta arquitectura los dos nodos RabbitMQ son contenedores en la EC2 universitaria, junto con los tres servicios Java. El clúster permite demostrar que ambos nodos se integran, pero la aplicación se conecta al nodo 1 y las colas configuradas son durables, no replicadas. Por lo tanto, esto cumple la demostración académica del clúster, pero no implementa failover de colas ni alta disponibilidad ante la caída del nodo o de la EC2. La memoria de la instancia también limita cuántos servicios pueden correr a la vez.

1. Copia el repo actualizado a la EC2 y verifica que tenga Docker Engine, el plugin Compose v2 y Java 21.
2. Crea un `.env` en el directorio del `docker-compose.yml`; genera una cookie Erlang larga y una contraseña propia. No subas `.env` a Git ni uses los valores locales en la nube.
3. Ejecuta `docker compose up -d` y verifica `docker compose ps` y `docker exec pedidos360-rabbit1 rabbitmqctl cluster_status`. Deben aparecer `rabbit@rabbit1` y `rabbit@rabbit2` como nodos del clúster.
4. En el Security Group del broker permite TCP `5672` solo desde el Security Group del backend. Para SSH usa la misma regla restringida que te permite conectar por EC2 Instance Connect. No abras `5673`, `15672`, `15673`, `4369` ni `25672` a Internet. Los nodos se comunican por la red Docker interna. Para ver la consola remotamente, crea un túnel SSH: `ssh -L 15672:127.0.0.1:15672 <usuario-ec2>@<ip-publica-broker>` y abre `http://localhost:15672`.
5. En el `.env` del broker configura `RABBITMQ_BIND_ADDRESS=<ip-privada-broker>`; en `pedidos-service` y `rabbit-admin-service` de la EC2 backend configura `RABBITMQ_HOST=<ip-privada-broker>`, `RABBITMQ_PORT=5672`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` y `RABBITMQ_VHOST=/pedidos360`. Activa el perfil de producción de pedidos y configura MySQL con sus variables existentes. Productos no necesita conexión RabbitMQ.
6. Compila los tres JAR localmente con Java 21 y Maven, cópialos a la EC2, y reinicia los servicios Java usando las variables de entorno. El servicio administrador escucha en el puerto `8083`.
7. Agrega al API Gateway una ruta hacia `8083` para los endpoints `/admin/*` solo si necesitas administrar colas desde fuera de la instancia; conserva validación JWT y rol `Admin`. No expongas `8083` directamente a Internet. El frontend GitHub Pages y el registro SPA de Entra ID no requieren cambios para el flujo actual de pedidos/productos.
8. Verifica `Overview` por el túnel SSH, confirma los dos nodos conectados y las colas/exchanges/DLQ, y crea un pedido desde el frontend. Revisa los logs de `pedidos-service`. Para probar una DLQ, publica un JSON inválido a una cola de trabajo y confirma que llegue a la DLQ correspondiente.
9. Vigila memoria y CPU con `free -h` y `docker stats`. Si la instancia se queda sin memoria, no mantengas todos los servicios levantados simultáneamente durante la demostración; prioriza RabbitMQ y `pedidos-service`, que contiene productores y consumidores.

La mensajería local usa AMQP sin TLS; en AWS el acceso queda limitado por red privada y Security Groups. Para tráfico entre redes o cuentas distintas, configura TLS/certificados en el broker o usa un broker administrado antes de habilitar conexiones externas.
