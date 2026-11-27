# Bitácora de trabajo — Pedidos360

Un documento por tarea, para que quien siga no tenga que reconstruir el *por qué* leyendo
diffs. Cada uno dice qué se hizo, por qué, qué se verificó **y cómo**, y qué **no** se verificó.

El sistema son tres repositorios y la numeración es **compartida**: cada número existe en un
solo repo, y las dos mitades de una tarea (un endpoint y la pantalla que lo usa) se citan entre
sí. Este índice es el completo.

**¿Vienes a tomar trabajo?** Empieza por [PENDIENTES.md](PENDIENTES.md).

## Entrega 2.0.0 — consola React y arreglos del backend (18-11)

| # | Tarea | Repo | Documento | Commit |
|---|---|---|---|---|
| 01 | Rediseño de la consola a React + TypeScript | consola | `cloud-ev3-front/docs/trabajo/01-rediseno-consola-react.md` | `a710fb1` |
| 02 | El pago se confirma y vuelve al frontend | backend | [02-callback-webpay.md](02-callback-webpay.md) | `ce0b72f` |
| 03 | Espera creciente en la lectura de Operaciones | consola | `cloud-ev3-front/docs/trabajo/03-espera-creciente-operaciones.md` | `bb5bc5b` |
| 04 | Tests con Vitest | consola | `cloud-ev3-front/docs/trabajo/04-tests-vitest.md` | `0de2a16` |
| 05 | Actuator en los servicios y compose raíz | backend | [05-actuator-y-compose.md](05-actuator-y-compose.md) | `5bd9faf` |
| 06 | El sondeo de salud pasa a `/actuator/health` | consola | `cloud-ev3-front/docs/trabajo/06-sondeo-de-salud.md` | `c863e44` |
| 07 | Las dos Lambdas y su infraestructura | backend | [07-lambdas-telemetria.md](07-lambdas-telemetria.md) | `2f1d402` |
| 08 | Reconexión automática del WebSocket | consola | `cloud-ev3-front/docs/trabajo/08-reconexion-websocket.md` | `ca4ea79` |

## Entrega 3.0.0-rc.1 — integración EV3 (22-11)

| # | Tarea | Repo | Documento | Commits |
|---|---|---|---|---|
| 09 | Contrato de integración antes del código | backend | [09-contrato-integracion.md](09-contrato-integracion.md) | `ec7d8ee`, `5f2dfbc` |
| 10 | Antifraude por huella de tarjeta con ventana deslizante real | backend | [10-antifraude-por-tarjeta.md](10-antifraude-por-tarjeta.md) | `f83ace7` |
| 11 | Recomendaciones parecidas, DynamoDB y métricas por WebSocket | backend | [11-recomendaciones-dynamodb-websocket.md](11-recomendaciones-dynamodb-websocket.md) | `69874d1` |
| 12 | Kafka admin que modifica, y tópicos configurados por flujo | backend | [12-kafka-admin-y-topicos-por-flujo.md](12-kafka-admin-y-topicos-por-flujo.md) | `19a6e48`, `be64757`, `37c2a1d`, `7be1caa` |
| 13 | ms-productos: catálogo, stock y navegación | backend | [13-ms-productos.md](13-ms-productos.md) | `dff55d5` |
| 14 | ms-carrito: pedidos coreografiados por eventos | backend | [14-ms-carrito-pedidos-coreografia.md](14-ms-carrito-pedidos-coreografia.md) | `19e2dfe` |
| 15 | RabbitMQ: clúster, notificaciones con DLQ y administración | backend | [15-rabbitmq-notificaciones.md](15-rabbitmq-notificaciones.md) | `34c7d51`, `be64757` |
| 16 | La tienda pasa a React + TS sobre los microservicios reales | tienda | `pedidos360-tienda/docs/trabajo/16-tienda-react-integrada.md` | `472fc23` |
| 17 | El sistema completo levantado de verdad, y lo que destapó | backend | [17-compose-primer-arranque-real.md](17-compose-primer-arranque-real.md) | `6116e80`, `5d55d50`, `4c027b4`, `e8016ed` |
| 18 | Identidad con Cognito, y tres fallos que el gateway escondía | backend + tienda | [18-cognito-identidad-en-el-borde.md](18-cognito-identidad-en-el-borde.md) | `9ecea47` · tienda `bc8ccce`, `958df92` |
| 19 | Registro de cuentas, y tres errores que se disfrazaban de "microservicio caído" | backend + tienda | [19-registro-y-errores-disfrazados.md](19-registro-y-errores-disfrazados.md) | `ba1b61e` · tienda `4077815`, `5d6a0e5`, `efbff63` |
| 20 | El puerto de la tienda, y por qué las colas son tres | backend | [20-puerto-de-la-tienda-y-las-tres-colas.md](20-puerto-de-la-tienda-y-las-tres-colas.md) | (esta entrega) |

## Feedback de la nota 3 (27-11)

| # | Tarea | Repo | Documento | Commits |
|---|---|---|---|---|
| 21 | Auditoría RabbitMQ → Kafka → DynamoDB, antifraude también en Webpay de la tienda, DynamoDB real, vuelta de Webpay, campana por sondeo, botones de demo | backend + tienda | [21-feedback-nota3-auditoria-antifraude-dynamodb.md](21-feedback-nota3-auditoria-antifraude-dynamodb.md) | `02ec5a4`, `af99b2b`, `961eeb4` · tienda `22d14c1`, `2656ab7`, `d783467` |

Mitades que van juntas: **10 ↔ 16** (origen `TIENDA` y `/callback`), **11 ↔ 16** (recomendaciones
con motivo), **13/14 ↔ 16** (catálogo, carrito, pedidos), **15 ↔ 16** (campana SSE), **17 ↔ 16**
(insignia de salud y CORS de Actuator), **11 ↔ P1 de PENDIENTES** (la consola debe pintar el
snapshot nuevo).

Siguiente número libre: **22**.

## Lo que conviene saber antes de tocar algo

- **Los tres repos se levantan juntos.** El compose del backend construye la consola y la tienda
  desde `CONSOLA_DIR` y `TIENDA_DIR` (`.env`). Clonarlos uno al lado del otro.
- **El contrato manda.** Nombres de tópico, grupos, campos de evento y puertos están en
  `PLAN-INTEGRACION-EV3.md`; no hay librería compartida que falle al compilar si uno se desalinea.
- **Qué se ejecutó en infraestructura real**: el sistema completo en Docker local el 22-11
  (doc 17, evidencias en `evidencias/ev3-docker/`) y el despliegue en **AWS** de la cuenta
  851725287694 (`18.211.30.140`, gateway `vih5lem02j`) con los scripts de `despliegue/`,
  el API Gateway y Cognito, verificado el 26/27-11 (docs 18 y 19). **No** se ejecutó el
  Terraform de telemetría (P3, cerrado por no exigirlo la pauta). DynamoDB real sí: 4 tablas en la
  cuenta 851725287694 desde el 27-11 (doc 21).
- **Maven con JDK 23 o superior**: los `pom.xml` fijan Lombok 1.18.42 y `<proc>full</proc>`. En
  Windows con git-bash, `cmd.exe /c mvn.cmd …`.
- **Cada documento tiene una sección "No verificado".** Antes de asumir que algo funciona en un
  caso que no es el de la demo, léela.

## Resuelto desde la entrega 2.0.0

| Pendiente de entonces | Cómo quedó |
|---|---|
| Ningún microservicio escribía en `EventosOperaciones` | ms-recomendaciones escribe un snapshot cada 5 s con `DYNAMODB_PUBLICAR_OPERACIONES=true` (doc 11) |
| Código de comercio de Transbank duplicado en el código | `app.webpay.codigo-comercio` / `WEBPAY_CODIGO_COMERCIO`, en un solo lugar |
| ms-pago y ms-recomendaciones compartían `pagos_db` | una base por servicio; ms-recomendaciones pasó a DynamoDB |
| `evidencias/` desactualizada | evidencias nuevas en `evidencias/ev3-docker/`; las anteriores quedan como historia de la entrega 2.0.0 |
