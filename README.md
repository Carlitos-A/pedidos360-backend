# Pedidos360 — Backend

Microservicios Spring Boot del sistema Pedidos360 (EP1 · DSY1107).

## Microservicios

| Servicio | Puerto | Endpoints |
|---|---|---|
| `pedidos-service` | 8081 | `GET /pedidos`, `POST /pedidos` |
| `productos-service` | 8082 | `GET /productos`, `POST /productos` |

## Requisitos

- Java JDK 21
- Maven (incluido vía `mvnw.cmd`)

## Configuración previa (obligatoria)

En `src/main/resources/application.yml` de cada servicio, reemplazar:

- `PEGA_AQUI_TENANT_ID` → Directory (tenant) ID de tu Entra ID
- `PEGA_AQUI_CLIENT_ID_DEL_API` → Application (client) ID de la app `Pedidos360-API`

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

H2 en memoria para desarrollo. En despliegue se migra a BD cloud
(RDS/Azure SQL) con credenciales por variables de entorno.
