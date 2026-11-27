# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
[versionado semántico](https://semver.org/lang/es/). La versión mayor sigue a la evaluación
del ramo: **3.x = Evaluación 3**.

## Compatibilidad entre repositorios

El sistema son tres repos que se despliegan juntos. Estas versiones están probadas entre sí:

| cloud-ev3-kafka | pedidos360-tienda | cloud-ev3-front (consola) | Estado |
|---|---|---|---|
| `v3.0.0-rc.1` | `v3.0.0-rc.1` | `v2.0.0` | probado con `docker compose up` (doc 17). La consola funciona pero describe parte del sistema anterior: ver P1 en `docs/trabajo/PENDIENTES.md` |

## [Sin publicar]

Lo que se haga desde `PENDIENTES.md` va acá hasta la entrega, que se etiqueta `v3.0.0`.

### Agregado

- **Feedback de la nota 3** (doc `21`):
  - **Auditoría RabbitMQ → Kafka → DynamoDB.** ms-notificaciones reenvía cada mensaje de la cola
    `notificaciones.auditoria` al tópico nuevo `auditoria-eventos` (3 particiones, RF 3, 90 d, lz4, con
    DLT). El envío es síncrono con `acks=all`, así que no hay ack en Rabbit hasta que Kafka confirma.
    ms-recomendaciones (`grupo-auditoria-dynamo`) lo persiste en la tabla DynamoDB
    `pedidos360-auditoria` y lo expone en `GET /api/auditoria`.
  - **DynamoDB real en AWS.** ms-recomendaciones deja `dynamodb-local` en la EC2
    (`DYNAMODB_ENDPOINT=` vacío) y usa el rol de la instancia (`LabRole`). Quedan 4 tablas reales:
    `HistorialEventos`, `RecomendacionesUsuario`, `EventosOperaciones-desarrollo` y `pedidos360-auditoria`.
- `DELETE /api/alertas` en ms-pago: limpia bloqueos e intentos (sólo demo).

### Corregido

- **Tarjeta bloqueada por el antifraude se aprobaba en la tienda.** En el commit de Webpay, si los
  últimos 4 dígitos que devuelve Transbank coinciden con un bloqueo vigente, se anula el cobro
  (`refund`), se publica `RECHAZADO` y la tienda muestra el motivo. Un Webpay aprobado ya no levanta
  ese bloqueo. Con SIMULADA se revisa además la cuenta del cliente (bloqueos hechos con Webpay).

### Agregado (anterior)

- **Identidad con Cognito validada en el borde** (doc `18`, `evidencias/56`). User pool reproducible
  con `despliegue/17-cognito-pool.sh`, authorizer `COGNITO_USER_POOLS` en 11 operaciones con
  `despliegue/16-cognito-authorizer.sh`, y la tienda pidiendo el ID token sin SDK. El gateway
  reescribe `X-Usuario-Id` con `claims.email`: **los siete microservicios no cambiaron**, pero la
  cabecera dejó de ser falsificable. Cierra P4 en la tienda.
- `despliegue/15-respuestas-de-error.sh`: respuestas de error por código en las rutas del gateway.
- `infra/prueba-identidad.py`: 401/200, preflight, suplantación, compra completa y antifraude, todo
  por el gateway.
- Cinco rutas `/salud/<servicio>` en el gateway, para la insignia de salud de la tienda.

### Cambiado

- `docker-compose.yml` y `.env.example`: `COGNITO_REGION`, `COGNITO_CLIENT_ID` y `COGNITO_POOL_ID`
  como configuración de arranque. Con las tres vacías, local queda idéntico.
- `despliegue/14-rutas-frontend-ev3.sh`: 35 rutas (eran 29). Se agregaron los seis métodos que la
  tienda usa de verdad y que no estaban publicados, el filtro `SOLO_RUTA` y la idempotencia de
  `put-method`.

### Corregido

- **El gateway aplastaba todos los códigos de error a 200**, así que el 429 del antifraude llegaba
  al navegador como 200 y la tienda mostraba "Respuesta inesperada (HTTP 200)" en vez del bloqueo.
- **Seis métodos sin publicar** (`PATCH /api/carrito/items/{id}`, `DELETE /api/carrito`,
  `DELETE /api/carrito/cupon`, `PUT /api/carrito/envio`, `PUT /api/productos/{id}/stock`,
  `GET /api/productos/admin/stock`): daban 403 y uno de ellos es un paso del checkout.
- **La insignia de la tienda decía "modo demo · sin backend"** detrás del gateway, con los 21
  contenedores sanos: `/actuator/health` no se publica y el 403 sin CORS cuenta como fallo de red.
- `API_BASE` no estaba en el `.env` de la instancia: la tienda iba a los puertos directos y F1 no
  estaba activo aunque la documentación lo diera por hecho.

## [3.0.0-rc.1] — 2026-11-22

Integración completa de la EV3: siete microservicios sobre Kafka, RabbitMQ y REST, levantados
y verificados juntos por primera vez.

### Agregado

- `PLAN-INTEGRACION-EV3.md`: contrato de tópicos, grupos, DTOs, colas, puertos y API, mapeado a
  la pauta EP5 y al enunciado (doc 09).
- **ms-productos** (8086): catálogo de 20 productos en 8 categorías, stock con reservas todo o
  nada, evento `VER_PRODUCTO` y snapshots al tópico compactado `catalogo-productos` (doc 13).
- **ms-carrito** (8087): carrito, cupones, envío o retiro y pedidos coreografiados por
  `pagos-resultado`; publica la navegación en Kafka y las notificaciones en RabbitMQ (doc 14).
- **RabbitMQ**: clúster de 3 nodos, colas quorum con DLX/DLQ; **ms-notificaciones** (8088) con
  reintentos, DLQ registrada en log y en base, y SSE; **ms-rabbit-admin** (8089) para colas,
  exchanges y bindings (doc 15).
- ms-kafka-admin: modificar configuración de tópicos, aumentar particiones, detalle, reset de
  offsets y borrado de consumer groups, configuración de brokers (doc 12).
- `infra/kafka/crear-topicos.sh`: 4 tópicos y 4 DLT con configuración distinta por flujo.
- ms-recomendaciones: DynamoDB, métricas en vivo por WebSocket `/ws/metricas` y simulador de
  tráfico (doc 11).
- ms-pago: pasarela simulada, tópico `pagos-resultado` y retorno a tienda o consola por lista
  cerrada (doc 10).
- Compose de 22 contenedores, `infra/prueba-humo.py` y evidencias del primer arranque real en
  `evidencias/ev3-docker/` (doc 17).
- Scripts de despliegue en AWS por SSM en `despliegue/` (Ismael, integrados desde `master`).

### Cambiado

- **Antifraude**: por huella SHA-256 de la tarjeta (antes por cliente), ventana deslizante
  real (antes sólo el intento anterior), bloquea en el 4.º intento (antes en el 3.º).
- **Recomendaciones**: productos parecidos por categoría, subcategoría y marca con su motivo
  (antes el top 5 de lo ya visto); catálogo de electrónica en vez de ropa y hogar.
- Todos los consumidores: `ErrorHandlingDeserializer`, reintentos con espera exponencial, DLT y
  consumidor del DLT; productores con `acks=all`, idempotencia y callback con partición y offset.
- Consumer groups renombrados por dominio y con `concurrency` (`grupo-antifraude`,
  `grupo-recomendaciones`, `grupo-dashboard`…).
- Una base de datos por servicio (antes ms-pago y ms-recomendaciones compartían `pagos_db`);
  ms-recomendaciones pasa de MySQL a DynamoDB.
- Código de comercio de Transbank leído de configuración (`WEBPAY_CODIGO_COMERCIO`).
- README reescrito para la arquitectura nueva.

### Corregido

- Imágenes Docker: `useradd -u 1000` fallaba sobre Ubuntu 26.04; UID 10001.
- CORS de `/actuator/**` declarado donde Spring Boot lo lee; los frontends mostraban los
  servicios caídos estando arriba.
- Plantilla del DLT de ms-pago que fallaba en cada envío y trababa la partición; DLT de
  ms-recomendaciones que nunca publicaba (destapados por las pruebas con Kafka embebido).

### Eliminado

- `ProductoConfig` (catálogo fijo en código) de ms-recomendaciones.
- Grupos `payment-velocity-group` y `recommendation-consumer-group`.

## [2.0.0] — 2026-11-18

Consola React, vuelta de Webpay confirmada, endpoints de métricas y alertas, Actuator, compose
raíz y Lambdas de telemetría con Terraform (docs 01–08).

## [1.0.0] — 2026-11-13

Clúster Kafka de 3 brokers y microservicios de pago, recomendaciones y administración.
