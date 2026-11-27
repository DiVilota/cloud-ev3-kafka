# Pedidos360 — Evaluación 3 · DSY1107 Desarrollo Cloud Native

**Pedidos360** es una tienda de tecnología construida como sistema *cloud native*. Tiene **siete
microservicios Spring Boot** que se comunican por **Kafka** (eventos), **RabbitMQ**
(notificaciones) y REST (comandos), y usan **MySQL** y **DynamoDB**. Hay dos frontends en React:
la **tienda** para el cliente y la **consola** de operaciones. Todo corre en **AWS** (Learner Lab):
una EC2 con 22 contenedores, el **API Gateway** con **Cognito** como puerta de entrada, y
**DynamoDB** gestionado.

Equipo: **Diego Villota** · **Ismael** (consola, `1samadhi`).

---

## 1. Qué revisar (ramas de la entrega)

El sistema son **tres repositorios que se despliegan juntos**. Estas son las ramas evaluadas, las
mismas que corren en AWS:

| Repositorio | Rama | Qué contiene |
|---|---|---|
| **[DiVilota/cloud-ev3-kafka](https://github.com/DiVilota/cloud-ev3-kafka/tree/p4-cognito-identidad)** (este) | **`p4-cognito-identidad`** | 7 microservicios, compose, tópicos, scripts de despliegue AWS, Lambdas, documentación |
| [DiVilota/pedidos360-tienda](https://github.com/DiVilota/pedidos360-tienda/tree/p4-cognito) | `p4-cognito` | tienda (React 19 + TypeScript + Vite): login con Cognito y con Microsoft (Azure Entra ID) |
| [DiVilota/cloud-ev3-front](https://github.com/DiVilota/cloud-ev3-front/tree/ev3-integracion) | `ev3-integracion` | consola de operaciones (React + TypeScript), fork de `1samadhi/cloud-ev3-front` |

> `master` es la línea de la entrega anterior. Lo de la EV3 está en las ramas de arriba.

### Sistema en vivo

| Qué | URL |
|---|---|
| **Tienda, por el API Gateway** (recomendada) | https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo/ |
| Tienda directa | http://18.211.30.140:8091 · HTTPS (certificado propio) en https://18.211.30.140:8443 |
| Consola de operaciones | http://18.211.30.140:8090 |
| Kafka UI | http://18.211.30.140:8080 |
| RabbitMQ management | http://18.211.30.140:15672 |

> Es un **AWS Academy Learner Lab**: la instancia sólo responde mientras el lab está iniciado. La
> IP es fija (Elastic IP). Las cuentas de demo (cliente y admin) y sus contraseñas se entregan
> aparte y no están en git. También se puede crear una cuenta desde *Registrarse* en la tienda.

---

## 2. Arquitectura

```
                         Navegador
          ┌────────────────────┴─────────────────────┐
     Tienda (React)                          Consola (React)
     login Cognito / Microsoft Entra ID      operaciones y administración
          │ HTTPS + ID token (JWT)                   │
          ▼                                          │
   API Gateway REST  vih5lem02j/desarrollo           │
   authorizer Cognito → reescribe X-Usuario-Id       │
   CORS por origen, respuestas de error por código   │
          │                                          │
══════════╪══════════ EC2 t3.large · Docker Compose · 22 contenedores ════════════
          ▼                                          ▼
  ms-productos  ms-carrito  ms-pago  ms-recomendaciones  ms-notificaciones
    :8086        :8087       :8083        :8084              :8088
  ms-kafka-admin :8085                    ms-rabbit-admin :8089
          │            │          │            │                 │
          ▼            ▼          ▼            ▼                 ▼
   Kafka: 3 brokers + 3 ZooKeeper (RF 3, min.insync 2)    RabbitMQ: 3 nodos, colas quorum
   MySQL 8: una base por servicio
══════════════════════════════════════════════════════════════════════════════════
          │
          ▼
   DynamoDB (AWS real): HistorialEventos · RecomendacionesUsuario ·
                        EventosOperaciones-desarrollo · pedidos360-auditoria
```

### Los tres canales y por qué cada uno

| Canal | Para qué | Por qué ese |
|---|---|---|
| REST | comandos que necesitan respuesta: reservar stock, crear pedido, iniciar pago | el cliente tiene que saber en el momento si hay stock |
| **Kafka** | eventos: navegación, intentos de pago, resultados, catálogo, **auditoría** | log persistente con *replay* y *fan-out*: varios grupos leen lo mismo con offsets independientes |
| **RabbitMQ** | notificaciones al cliente | entrega por mensaje con confirmación, reintentos y DLQ |

Son **dominios de falla independientes**: si RabbitMQ o un consumidor cae, la compra se completa
igual y los mensajes esperan en la cola (verificado, doc 17).

### Flujo de una compra (coreografía)

```
tienda ─REST→ ms-carrito: reserva stock (ms-productos) → pedido PENDIENTE_PAGO
tienda ─REST→ ms-pago: antifraude → payment-attempts (Kafka) → Webpay / pasarela simulada
ms-pago ──→ pagos-resultado (Kafka) ──→ ms-carrito: pedido PAGADO o PAGO_RECHAZADO (libera stock)
ms-carrito ──→ RabbitMQ pedidos360.notificaciones ──┬→ notificaciones.cliente  → campana de la tienda
                                                    └→ notificaciones.auditoria → Kafka auditoria-eventos → DynamoDB
ms-carrito: un proceso programado avanza el envío y notifica cada cambio de estado
```

---

## 3. Qué hay en AWS

| Servicio | Uso |
|---|---|
| **EC2** `i-0f5f47b9ffdefa327` (t3.large, 40 GB) + **Elastic IP** `18.211.30.140` | el sistema completo con Docker Compose. Se administra sólo por **SSM**: sin SSH, puerto 22 cerrado |
| **API Gateway REST** `vih5lem02j`, etapa `desarrollo` | puerta de entrada de la tienda: rutas `/api/*` explícitas hacia los servicios, CORS por origen y respuestas de error con cabeceras CORS. También sirve la propia tienda desde la URL de la etapa |
| **Cognito** user pool `us-east-1_Vaxf6Jhxx` | registro y login, con grupos `cliente`/`admin`. El gateway valida el JWT y **reescribe `X-Usuario-Id`** con el claim `email`: la identidad no se puede falsificar y los servicios no tuvieron que cambiar |
| **DynamoDB** (4 tablas, on-demand) | eventos de alto volumen y acceso por clave (sección 6) |
| **IAM** `LabInstanceProfile` → `LabRole` | credenciales de los contenedores para DynamoDB. El SDK las renueva solo; no hay claves en el `.env` |
| **Microsoft Entra ID** (Azure, tenant Duoc UC) | botón "Iniciar sesión con Microsoft" en la tienda (MSAL.js). Azure exige HTTPS fuera de localhost, por eso se usa la URL del gateway |
| Métricas en vivo | `ms-recomendaciones` empuja un snapshot por WebSocket (`/ws/metricas`) cada 2 s (`evidencias/53`) |

Los scripts de despliegue están en [`despliegue/`](despliegue/LEEME-DESPLIEGUE.md), numerados del
`00` al `21` y todos ejecutados por SSM. El estado detallado está en
[`ESTADO-DESPLIEGUE-DIEGO.md`](ESTADO-DESPLIEGUE-DIEGO.md).

---

## 4. Cambios después de la presentación (feedback de la nota 3)

Detalle completo, con causas, decisiones y verificación, en
[`docs/trabajo/21-feedback-nota3-auditoria-antifraude-dynamodb.md`](docs/trabajo/21-feedback-nota3-auditoria-antifraude-dynamodb.md).

| # | Observación | Qué se hizo |
|---|---|---|
| 1 | **Cola RabbitMQ de AUDITORÍA a un tópico de Kafka** | `ms-notificaciones` reenvía cada mensaje de `notificaciones.auditoria` al tópico nuevo **`auditoria-eventos`** (3 particiones, RF 3, 90 días, lz4, con DLT). Envío síncrono con `acks=all`: RabbitMQ sólo recibe el ack cuando Kafka confirmó. `ms-recomendaciones` (grupo `grupo-auditoria-dynamo`) lo persiste en DynamoDB. Cadena completa: **RabbitMQ → Kafka → DynamoDB** |
| 2 | **Tarjeta bloqueada por el antifraude se aprobaba en la tienda** | **Causa:** con Webpay la huella es la *cuenta* (Transbank no entrega la tarjeta antes de pagar), así que el bloqueo de la consola no alcanzaba a la tienda. **Arreglo:** en el commit de Webpay, si los últimos 4 dígitos que devuelve Transbank coinciden con un bloqueo vigente, se **anula el cobro** (`refund`), el pago queda `RECHAZADO`, ms-carrito libera el stock y la tienda muestra el motivo. Con la pasarela simulada también se revisa la cuenta. Un Webpay aprobado ya no levanta un bloqueo de tarjeta |
| 3 | **No había tablas DynamoDB en AWS** | ms-recomendaciones dejó el emulador y usa el **DynamoDB real** con el rol de la instancia. Quedan **4 tablas**: las 3 de recomendaciones y métricas, más `pedidos360-auditoria` |

Ajustes que salieron al probarlo en la tienda:

- **La vuelta de Webpay cerraba la sesión.** Volvía a `IP:8091`, un origen distinto al del gateway.
  Ahora vuelve a la URL de la etapa (`URL_TIENDA_PUBLICA`).
- **Campana de notificaciones.** Las llamadas mandan el ID token (daban 401 en el gateway). Como el
  API Gateway REST no transmite SSE, por el gateway la tienda consulta el listado cada 5 s; en
  directo sigue en vivo por SSE.
- **Botones de demo en el checkout:** *Ráfaga* (4 intentos en 10 s con la misma tarjeta) y
  *Resetear antifraude* (`DELETE /api/alertas`, protegido por Cognito en el gateway).

### ¿Por qué no se migró todo MySQL a DynamoDB?

Es **persistencia políglota**, a propósito:

- **MySQL** para lo **transaccional y relacional**: la reserva de stock (`@Transactional`, todo o
  nada), pedidos con ítems y estados, pagos y antifraude. Necesita atomicidad entre filas,
  unicidad y consultas por varios campos.
- **DynamoDB** para **eventos de alto volumen con acceso por clave**: historial de interacciones,
  recomendaciones precalculadas, métricas y auditoría. Son escrituras *append-only* que crecen sin
  límite y se consultan por PK; DynamoDB escala sin administrar servidores y el TTL las limpia solo.

---

## 5. Cómo probarlo (guion de la demo)

1. **Comprar.** Abrir la tienda por el gateway e iniciar sesión (o registrarse, o entrar con
   Microsoft). Agregar productos, aplicar un cupón (`BIENVENIDA10`, `ENVIOGRATIS`, `GAMER5000`),
   elegir despacho o retiro y pagar:
   - **Webpay** (integración Transbank, sin dinero real): VISA `4051 8856 0044 6623`, CVV `123`,
     RUT `11.111.111-1`, clave `123`. Con MASTERCARD `5186 0595 5959 0568` Transbank la rechaza.
   - **Pasarela de prueba**: aprueba todo salvo las tarjetas terminadas en `0002`.
2. **Notificaciones.** La campana recibe *producto agregado*, *pedido creado*, *pedido pagado* y
   cada cambio de envío (avanza cada 20 s). *Mis pedidos* se actualiza solo.
3. **Antifraude.** En el checkout, *Ráfaga* devuelve `200 · 200 · 200 · 429`: la tarjeta queda
   bloqueada 30 min. Pagar el pedido con esa tarjeta:
   - con la pasarela de prueba → **429**, "Bloqueado por antifraude";
   - con **Webpay** → Transbank autoriza, ms-pago lo **anula** y la tienda muestra
     *"Pago rechazado: el sistema antifraude tiene bloqueada esta tarjeta"*.

   *Resetear antifraude* limpia los bloqueos. El panel de alertas también está en la consola.
4. **Auditoría RabbitMQ → Kafka → DynamoDB.** Cualquier acción del paso 1 genera eventos:
   - RabbitMQ management: cola `notificaciones.auditoria`;
   - Kafka UI: tópico `auditoria-eventos` (key = pedido o usuario; headers `rabbit-routing-key`,
     `rabbit-message-id`);
   - DynamoDB: tabla `pedidos360-auditoria`, o `GET :8084/api/auditoria/{idPedido|email}`.
5. **Consola de operaciones.** Clúster y tópicos Kafka (configuración, consumer groups, lag, reset
   de offsets), colas y exchanges de RabbitMQ, métricas en vivo por WebSocket, alertas de fraude
   y mensajes muertos.

Pruebas automáticas del flujo completo: `python infra/prueba-humo.py` (25 comprobaciones) e
`infra/prueba-identidad.py` (Cognito: 401/200, preflight, suplantación, compra y antifraude por
el gateway).

---

## 6. Componentes

```
docker-compose.yml           Sistema completo: 22 contenedores
infra/kafka/crear-topicos.sh Los 5 tópicos y sus DLT, configurados por flujo
infra/rabbitmq/              Clúster RabbitMQ de 3 nodos (también standalone)
infra/docker-compose.yml     Clúster Kafka solo: 3 ZooKeeper + 3 brokers + Kafka UI
infra/terraform-telemetria/  DynamoDB + Streams + Lambdas + API Gateway WebSocket (no aplicado en el lab)
infra/prueba-*.py            Pruebas de humo, identidad, registro y pago aprobado
despliegue/                  Scripts de despliegue en AWS por SSM (00 a 21)
lambda-telemetria/           Las dos Lambdas del dashboard en tiempo real
ms-*/                        Un microservicio por carpeta, cada uno con pom.xml y Dockerfile
docs/trabajo/                Bitácora: un documento por tarea (01 a 21)
evidencias/                  Salidas reales de las pruebas (Docker local y AWS)
```

| Servicio | Puerto | Kafka produce | Kafka consume (grupo) | RabbitMQ | Persistencia |
|---|---|---|---|---|---|
| ms-pago | 8083 | `payment-attempts`, `pagos-resultado` | `payment-attempts` (`grupo-antifraude`) | — | MySQL `pagos_db` |
| ms-recomendaciones | 8084 | `interacciones-user` (simulador) | `interacciones-user` (`grupo-recomendaciones`, `grupo-dashboard`), `catalogo-productos` (`grupo-catalogo-recomendaciones`), **`auditoria-eventos` (`grupo-auditoria-dynamo`)** | — | **DynamoDB** |
| ms-kafka-admin | 8085 | — | — (AdminClient) | — | — |
| ms-productos | 8086 | `interacciones-user` (VER_PRODUCTO), `catalogo-productos` | — | — | MySQL `productos_db` |
| ms-carrito | 8087 | `interacciones-user` (carrito, cupón, envío, compra) | `pagos-resultado` (`grupo-carrito`) | produce | MySQL `carrito_db` |
| ms-notificaciones | 8088 | **`auditoria-eventos`** | — | consume (cliente, auditoría, DLQ) | MySQL `notificaciones_db` |
| ms-rabbit-admin | 8089 | — | — | AmqpAdmin + management | — |

Cada consumidor Kafka tiene además su grupo `grupo-dlt-<servicio>`, que registra los mensajes muertos.

Stack: Java 21 · Spring Boot 3.5.6 · Confluent Kafka 7.5.0 · RabbitMQ 3.13 · MySQL 8.0 ·
DynamoDB (AWS SDK v2) · SDK Transbank 6.x · React 19 + TypeScript + Vite · nginx.

### Kafka: tópicos por flujo

| Tópico | Part. | Retención | Cleanup | Key | Por qué |
|---|---|---|---|---|---|
| `interacciones-user` | 6 | 7 d | delete + lz4 | usuario | el flujo de más volumen: más paralelismo |
| `payment-attempts` | 3 | 30 d | delete | huella de tarjeta | auditoría antifraude; orden por tarjeta para la ventana |
| `pagos-resultado` | 3 | 90 d | delete | pedido | registro de ventas |
| `catalogo-productos` | 3 | infinita | **compact** | producto | es estado: basta el último snapshot por producto |
| **`auditoria-eventos`** | 3 | 90 d | delete + lz4 | pedido / usuario | copia de todo lo que pasa por RabbitMQ, con replay |
| `<tópico>.DLT` ×5 | igual al origen | 14 d | delete | la original | el DLT publica en la misma partición del original |

Todos los tópicos usan factor de replicación 3 y `min.insync.replicas=2`, y los productores
`acks=all` con idempotencia. El porqué de cada valor está comentado en
`infra/kafka/crear-topicos.sh`.

**Manejo de errores en todos los consumidores:** `ErrorHandlingDeserializer`, reintentos con espera
exponencial para errores transitorios, envío directo al DLT de lo que no tiene arreglo (JSON roto,
payload inválido) y un consumidor del DLT que lo registra. Un mensaje defectuoso nunca traba su
partición.

### RabbitMQ

Exchange topic `pedidos360.notificaciones` → colas **quorum** en el clúster de 3 nodos:

| Cola | Binding | Consumidor |
|---|---|---|
| `notificaciones.cliente` | `carrito.*`, `pedido.*`, `envio.*` | guarda la notificación y la empuja por SSE a la campana |
| `notificaciones.auditoria` | `#` (todo) | log + **reenvío a Kafka `auditoria-eventos`** |
| `notificaciones.cliente.dlq` | DLX `pedidos360.notificaciones.dlx` | tras 3 reintentos: se registra con sus headers `x-death` |

### DynamoDB

| Tabla | PK / SK | Escribe |
|---|---|---|
| `HistorialEventos` | `usuarioId` / `instanteEvento` | consumidor de `interacciones-user` |
| `RecomendacionesUsuario` | `usuarioId` | motor de recomendaciones |
| `EventosOperaciones-desarrollo` | `tipo` / `ocurridoEn` (stream NEW_IMAGE, TTL) | snapshot de métricas (para la Lambda de telemetría) |
| `pedidos360-auditoria` | `entidad` / `ocurridoEn#messageId` (TTL 90 d) | consumidor de `auditoria-eventos` |

---

## 7. API por servicio

En AWS todo esto se publica por el API Gateway (`/api/*`). Detrás del gateway, la identidad sale
del JWT de Cognito.

### ms-pago — Webpay, pasarela simulada y antifraude

```
POST     /api/pagos/iniciar   { clientId, amount, transactionId, pasarela?, tarjeta?, origen? }
POST|GET /api/pagos/commit    vuelta de Transbank: confirma, revisa antifraude, publica y redirige
GET      /api/alertas         bloqueos vigentes y alertas de fraude
DELETE   /api/alertas         sólo demo: limpia bloqueos e intentos
```

**Regla antifraude** (enunciado: *la misma tarjeta, más de 3 veces en 10 segundos*): ventana
deslizante real sobre la **huella SHA-256 de la tarjeta** con *pepper*. El número nunca se guarda,
publica ni loguea. El **4.º** intento dentro de 10 s bloquea la huella 30 minutos y registra la
alerta. Con **Webpay** la tarjeta se reconoce en el commit por los últimos 4 dígitos (lo único que
Transbank entrega; PCI DSS), y un cobro con tarjeta bloqueada se anula.

| Código | Situación |
|---|---|
| 200 | Webpay: `{ url, token }` · Simulada: `{ estado: AUTORIZADO, idOrden, ultimos4 }` |
| 400 | payload inválido, con el detalle del campo |
| 402 | pago simulado rechazado (tarjetas terminadas en `0002`) |
| 429 | tarjeta o cuenta bloqueada por el antifraude |
| 503 | Kafka no confirmó el intento (sin evidencia no se cobra) |

```bash
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8083/api/pagos/iniciar \
    -H 'Content-Type: application/json' \
    -d "{\"clientId\":\"demo\",\"amount\":15000,\"transactionId\":\"TX-$i\",\"pasarela\":\"SIMULADA\",\"tarjeta\":{\"numero\":\"4051885600446623\"}}"
done
# 200 200 200 429
```

**Vuelta desde Webpay.** El `returnUrl` apunta a ms-pago, no al frontend, porque una página
estática no puede leer el cuerpo de un POST:

```
Webpay --POST token_ws--> :8083/api/pagos/commit --302--> <frontend>/callback?estado=...[&motivo=antifraude]
                                   └── publica ResultadoPagoDTO en pagos-resultado
```

El frontend de destino sale de una **lista cerrada**: nunca se redirige a una URL que mande el
cliente. Una anulación llega con `TBK_TOKEN` y sin `token_ws`, y se trata como `ANULADO`.

| Variable | Para qué |
|---|---|
| `PAGOS_RETURN_URL` | adonde vuelve Transbank (`http://<host>:8083/api/pagos/commit`) |
| `FRONT_URL` | consola, destino de los pagos con origen `CONSOLA` |
| `FRONT_URL_TIENDA` / `URL_TIENDA_PUBLICA` | tienda; en AWS, la URL de la etapa del gateway (misma sesión) |
| `WEBPAY_CODIGO_COMERCIO` | `597055555532` (integración) |

### ms-recomendaciones — recomendaciones, DynamoDB, métricas y auditoría

```
POST /api/interacciones             formato de la consola { clientId, itemId, action, ... }
POST /api/interacciones/simular     ?cantidad=50&usuarioId= — tráfico para la demo
GET  /api/recomendaciones?userId=   top 5 con nombre, precio y motivo
GET  /api/metricas                  snapshot de las métricas en vivo
WS   /ws/metricas                   el mismo snapshot empujado cada 2 s
GET  /api/auditoria?limite=20       muestra de la tabla pedidos360-auditoria
GET  /api/auditoria/{entidad}       eventos de un pedido o usuario, del más reciente al más antiguo
```

Perfil del usuario por categoría, subcategoría y marca con decaimiento temporal. Recomienda lo
**parecido**, excluye lo comprado y lo agotado, y explica el motivo ("Porque viste WH-1000XM5").
El catálogo se reconstruye leyendo el tópico compactado `catalogo-productos`.

### ms-productos — catálogo y stock

```
GET  /api/productos                 ?categoria=&q=&orden=relevancia|precio_asc|precio_desc
GET  /api/productos/categorias
GET  /api/productos/{id}            con X-Usuario-Id publica VER_PRODUCTO
POST /api/productos/stock/reservas      todo o nada; 409 con los faltantes
POST /api/productos/stock/liberaciones  idempotente por pedido
PUT  /api/productos/{id}/stock
GET  /api/productos/admin/stock
```

### ms-carrito — carrito, cupones, envío y pedidos

```
GET|DELETE  /api/carrito
POST        /api/carrito/items            { productoId, cantidad }
PATCH       /api/carrito/items/{id}       { cantidad }  (0 = quitar)
POST|DELETE /api/carrito/cupon            { codigo }    BIENVENIDA10 · ENVIOGRATIS · GAMER5000
PUT         /api/carrito/envio            { metodo: ENVIO|RETIRO, direccion, comuna, sucursal }
GET         /api/carrito/sucursales
POST        /api/pedidos                  reserva stock y deja el pedido en PENDIENTE_PAGO
GET         /api/pedidos · /api/pedidos/{id}
GET         /api/admin/resumen · /api/admin/pedidos     (grupo admin de Cognito)
```

El usuario llega en `X-Usuario-Id`; en AWS lo escribe el API Gateway desde el JWT, no el cliente.

### ms-notificaciones — consumidor RabbitMQ

```
GET   /api/notificaciones?usuarioId=
GET   /api/notificaciones/stream?usuarioId=    SSE (acceso directo; por el gateway la tienda sondea)
PATCH /api/notificaciones/{id}/leida
POST  /api/notificaciones/leidas?usuarioId=
GET   /api/notificaciones/fallidas              lo que terminó en la DLQ
```

### ms-rabbit-admin y ms-kafka-admin — administración

```
GET|POST /api/rabbit/colas · /exchanges · /bindings     DELETE y purgar; protege amq.* y la topología
GET      /api/rabbit/cluster

GET    /api/cluster · /api/brokers/{id}/configuracion
GET    /api/topicos · /api/topicos/{nombre}           POST crea (valida réplicas vs brokers vivos)
GET|PUT /api/topicos/{nombre}/configuracion           lista blanca tipada
POST   /api/topicos/{nombre}/particiones              sólo aumentar
GET    /api/consumer-groups · /{id} · /{id}/lag
POST   /api/consumer-groups/{id}/offsets/reset        EARLIEST|LATEST|TIMESTAMP|OFFSET
```

---

## 8. Levantarlo en local

Los frontends son repos aparte: hay que clonarlos **al lado** de este y apuntar el `.env` a ellos.

```bash
git clone -b p4-cognito-identidad https://github.com/DiVilota/cloud-ev3-kafka.git
git clone -b p4-cognito           https://github.com/DiVilota/pedidos360-tienda.git
git clone -b ev3-integracion      https://github.com/DiVilota/cloud-ev3-front.git
cd cloud-ev3-kafka
cp .env.example .env          # CONSOLA_DIR y TIENDA_DIR: rutas a los otros dos repos
docker compose up -d --build
python infra/prueba-humo.py   # 25 comprobaciones contra los servicios reales
```

El primer build tarda (siete servicios y dos frontends). El sistema usa ~4,6 GB de RAM. En local,
ms-recomendaciones usa `dynamodb-local`; con `DYNAMODB_ENDPOINT=` vacío usa el DynamoDB real.

| Qué | URL |
|---|---|
| Tienda | http://localhost:8091 |
| Consola | http://localhost:8090 |
| Kafka UI | http://localhost:8080 |
| RabbitMQ | http://localhost:15672 (`pedidos360` / `pedidos360`) |
| Microservicios | http://localhost:8083 … 8089 |

Orden de arranque, donde cada capa espera la **salud** de la anterior:

```
zookeeper1..3 → kafka1..3 → crear-topicos → kafka-ui
rabbitmq1..3 ───────────────────────┐
mysql · dynamodb-local ─────────────┴→ microservicios → tienda · consola
```

Apagar: `docker compose down` (conserva datos) · `docker compose down -v` (borra volúmenes).

### Pruebas

Cada servicio tiene pruebas que no necesitan infraestructura (Kafka embebido, H2, mocks):
`cd ms-pago && mvn -B test`.

| Servicio | Pruebas |
|---|---|
| ms-pago | 55 |
| ms-recomendaciones | 41 |
| ms-kafka-admin | 70 |
| ms-productos | 46 |
| ms-carrito | 81 |
| ms-notificaciones | 34 |
| ms-rabbit-admin | 93 |

La tienda y la consola tienen pruebas con Vitest (`npm test`).

---

## 9. Observaciones de la EV2, resueltas

| | Observación | Cómo quedó |
|---|---|---|
| F1 | API Gateway para el frontend | la tienda entra entera por el gateway (`despliegue/14`, `16`, `20`), con Cognito |
| F2 | No usar proxy en backend | ningún `proxy_pass` en los `nginx.conf` |
| F3 | CORS con la ruta de la API | el gateway reenvía `Origin` y devuelve el CORS del servicio, sin `*`, también en los errores (`15`) |
| F4 | Todas las colas deben funcionar | las tres colas RabbitMQ con mensajes reales (`evidencias/55`), y los DLT de Kafka (`evidencias/51`) |

---

## 10. Documentación

| Documento | Para qué |
|---|---|
| [`ARQUITECTURA-EV3.md`](ARQUITECTURA-EV3.md) | el mapa completo del sistema, servicio por servicio |
| [`ARQUITECTURA-PEDIDOS360.md`](ARQUITECTURA-PEDIDOS360.md) | el porqué de cada tecnología |
| [`PLAN-INTEGRACION-EV3.md`](PLAN-INTEGRACION-EV3.md) | el contrato: tópicos, grupos, DTOs, colas, puertos y API, mapeado a la pauta |
| [`GUIA-DEFENSA-EV3.md`](GUIA-DEFENSA-EV3.md) · [`KIT-ESTUDIO-EV3.md`](KIT-ESTUDIO-EV3.md) | preguntas de defensa y estudio (también en PDF) |
| [`docs/trabajo/`](docs/trabajo/README.md) | bitácora: un documento por tarea (01–21), con lo verificado y lo no verificado |
| [`docs/trabajo/PENDIENTES.md`](docs/trabajo/PENDIENTES.md) | estado de cada pendiente |
| [`despliegue/LEEME-DESPLIEGUE.md`](despliegue/LEEME-DESPLIEGUE.md) | desplegar en un Learner Lab propio |
| [`ESTADO-DESPLIEGUE-DIEGO.md`](ESTADO-DESPLIEGUE-DIEGO.md) | el despliegue principal (cuenta 851725287694) |
| [`evidencias/`](evidencias/) | salidas reales: antifraude, consumer groups, DLT, DynamoDB, WebSocket, colas, Cognito, Webpay aprobado |
| [`CHANGELOG.md`](CHANGELOG.md) | versiones y compatibilidad entre los tres repos |

---

## 11. Limitaciones conocidas

- **La consola no pasa por el gateway ni tiene login.** Usa rutas de administración que el gateway
  no publica y entra directo a los puertos de los servicios (P1 en `PENDIENTES.md`).
- **SSE por el API Gateway REST no es posible**: el gateway junta la respuesta completa y corta a
  los 29 s. Por eso, a través del gateway la tienda sondea las notificaciones cada 5 s.
- **Antifraude con Webpay por los últimos 4 dígitos**: dos tarjetas con la misma terminación
  chocan mientras dura un bloqueo (30 min). Es lo máximo posible sin guardar el número (PCI DSS).
- **`DELETE /api/alertas` borra todo el antifraude**: existe sólo para la demo y está detrás de
  Cognito.
- **Telemetría con Lambdas** (`lambda-telemetria/`, `infra/terraform-telemetria/`): el código y el
  Terraform están escritos, pero **no se aplicaron** en el Learner Lab, que no permite crear roles
  IAM. Las métricas en vivo se sirven por el WebSocket de `ms-recomendaciones`.
- Defectos menores documentados en `PENDIENTES.md` (P8, P9).

## 12. Notas de configuración

- **Brokers según dónde corre el cliente:** `localhost:9092-9094` desde el host, `kafkaN:29092`
  desde un contenedor, `<ip-pública>:9092` hacia la EC2 (`KAFKA_HOST`). `bootstrap.servers` va sin
  esquema.
- **Los tópicos los crea `crear-topicos`**, no `auto.create.topics`: la pauta pide configuración
  por flujo. Es idempotente.
- **Lo que abre el navegador** (`FRONT_URL*`, `PAGOS_RETURN_URL`, `API_*`) va con la dirección
  pública, nunca con nombres de servicio de Docker. `API_BASE` en el `.env` manda los frontends por
  el gateway.
- **Credenciales fuera del código**: variables de entorno (`.env`, ignorado por git). DynamoDB usa
  el rol de la instancia, sin claves.
- **CORS con orígenes concretos** (`CORS_ORIGENES`), declarado también para `/actuator/**`, que no
  pasa por el `CorsConfig` de MVC.
- **Actuator expone sólo `health`**. `ms-kafka-admin` agrega `KafkaClusterHealthIndicator`, que
  reporta DOWN con menos de 2 brokers porque así no se puede honrar `min.insync.replicas=2`.
- **Healthcheck de ZooKeeper con `srvr`**, no con `ruok` (fuera de la lista permitida desde 3.5).
- Con **JDK 23 o superior** los `pom.xml` fijan Lombok 1.18.42 y `<proc>full</proc>`.
