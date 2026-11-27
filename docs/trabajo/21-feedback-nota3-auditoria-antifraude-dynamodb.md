# 21 · Feedback de la nota 3: auditoría a Kafka, antifraude en la tienda y DynamoDB real

Tres observaciones del profesor después de la presentación (27-11-2026), resueltas el mismo día.

| # | Observación | Estado |
|---|---|---|
| 1 | La cola RabbitMQ de AUDITORÍA debe llegar a un tópico de Kafka | ✅ `notificaciones.auditoria` → `auditoria-eventos` |
| 2 | Tarjeta bloqueada por el antifraude en la consola se aprobaba al comprar en la tienda | ✅ corregido en ms-pago (SIMULADA y Webpay) |
| 3 | No había ninguna tabla DynamoDB en AWS | ✅ 4 tablas reales en la cuenta del Learner Lab |

---

## 1. RabbitMQ (auditoría) → Kafka

```
ms-carrito ──publish──▶ exchange pedidos360.notificaciones (topic)
                           ├─[pedido.* / carrito.* …]─▶ notificaciones.cliente   → campanita / SSE
                           └─[#]──────────────────────▶ notificaciones.auditoria
                                                            │  ConsumidorAuditoria (ms-notificaciones)
                                                            ▼  PuenteAuditoriaKafka  acks=all, idempotente
                                                       Kafka: auditoria-eventos (3 part., RF 3, 90 d, lz4)
                                                            │  grupo-auditoria-dynamo (ms-recomendaciones)
                                                            ▼
                                                       DynamoDB: pedidos360-auditoria
```

- **Por qué Kafka además de RabbitMQ.** RabbitMQ entrega y borra: sirve para reaccionar
  (notificar al cliente). La auditoría necesita *historia*: Kafka retiene 90 días, se puede
  releer desde el offset 0 (replay) y varios grupos la consumen sin quitársela al otro.
- **No se pierde nada.** El envío a Kafka es síncrono (`get(5s)`) con `acks=all` y
  `min.insync.replicas=2`. El listener de Rabbit sólo hace ack cuando Kafka confirmó; si Kafka no
  responde se lanza la excepción y el reintento del consumidor (3 intentos, backoff) lo repite.
- **Key** del registro: `idPedido` si viene; si no `usuarioId`; si no el `messageId`. Todos los
  eventos de un pedido quedan en la misma partición y en orden.
- **Headers**: `rabbit-routing-key`, `rabbit-message-id`, `origen` (visibles en Kafka UI).
- Interruptor: `AUDITORIA_KAFKA_HABILITADO=true` (lo pone el compose; las pruebas corren sin broker).

Evidencia:

```bash
docker exec kafka1 kafka-console-consumer --bootstrap-server kafka1:29092 \
  --topic auditoria-eventos --from-beginning --property print.key=true --property print.headers=true
```

## 2. Antifraude: la tarjeta bloqueada se aprobaba en la tienda

### Causa (vista en los logs de ms-pago)

La huella del antifraude depende de la pasarela:

| Pasarela | Huella | Por qué |
|---|---|---|
| SIMULADA | `SHA-256(número + pepper)` | ms-pago recibe el número |
| WEBPAY | `SHA-256("cuenta:" + clientId + pepper)` | Transbank captura la tarjeta en su formulario (PCI DSS): al iniciar no hay número |

La ráfaga de la consola se había hecho en modo **Webpay** con el cliente `ismael`. Eso bloqueó la
**cuenta** `ismael`, no la tarjeta (`ALERTA FRAUDE … ultimos4=null … pasarela=WEBPAY`). Después,
en la tienda, `prueba@gmail.com` pagó con la tarjeta `…6623`, que tiene otra huella, y se aprobó.
Con Webpay había además un segundo agujero: ms-pago nunca miraba la tarjeta, ni siquiera al volver
de Transbank.

### Corrección

1. **Webpay, en el commit.** Transbank devuelve los últimos 4 dígitos (`card_detail.card_number`).
   Si hay un bloqueo **vigente** con esos 4 dígitos:
   - se anula el cobro con `refund` en Transbank,
   - se publica `RECHAZADO` en `pagos-resultado`, y ms-carrito libera el stock,
   - **no** se limpia la ventana ni se levanta el bloqueo (antes un Webpay aprobado lo limpiaba),
   - la tienda recibe `estado=REJECTED&motivo=antifraude` y lo explica.
2. **SIMULADA.** Además de la huella de la tarjeta se revisa la **cuenta** del cliente, así que un
   bloqueo hecho con Webpay también corta los pagos simulados de esa cuenta.

*Trade-off*: con Webpay sólo se conocen 4 dígitos, así que dos tarjetas con la misma terminación
chocan **mientras dura el bloqueo (30 min)**. Se prefiere rechazar de más a cobrar con una
tarjeta marcada como fraude, y es lo máximo que permite PCI DSS sin guardar el número.

### Ajustes durante la prueba en la tienda

- **La vuelta de Webpay perdía la sesión.** ms-pago redirigía a `http://IP:8091/callback`, pero la
  tienda se abre por la etapa del API Gateway y la sesión de Cognito vive en el `localStorage` de
  ese origen. Ahora `URL_TIENDA_PUBLICA` (en el `.env` de la EC2) fija la vuelta a
  `https://<api>.execute-api.us-east-1.amazonaws.com/desarrollo/callback`.
- **Botones de demo en el checkout**: *Ráfaga* hace 4 intentos SIMULADA con la tarjeta (órdenes
  `RAFAGA-*`, que ms-carrito ignora) y *Resetear antifraude* llama a `DELETE /api/alertas`,
  publicado en el gateway con el authorizer de Cognito (`despliegue/21-reset-antifraude-gateway.sh`).
- **Campana de notificaciones**: las llamadas ahora mandan el ID token (daban 401 en el gateway).
  Como el API Gateway REST no transmite SSE, por el gateway la tienda consulta el listado cada 5 s.

Pruebas: `ServicioPagosTest.commitWebpayConTarjetaBloqueadaAnulaYPublicaRechazado`,
`ServicioPagosTest.simuladaRevisaTambienLaCuentaDelCliente`.

## 3. DynamoDB real en AWS

ms-recomendaciones ya estaba escrito para DynamoDB, pero en la EC2 apuntaba al contenedor
`dynamodb-local`. Cambios:

- `.env` de la EC2: `DYNAMODB_ENDPOINT=` (vacío), y el SDK va al DynamoDB real de `us-east-1`.
  Si se regenera el `.env` (`02-sistema-ev3.sh`, rama `p2-despliegue`), hay que volver a agregar esa línea.
  Sin ella, el compose vuelve a `dynamodb-local`.
- `DynamoDbConfig`: credenciales del **rol de la instancia** (`LabInstanceProfile` → `LabRole`)
  primero. El SDK las renueva solo: las del Learner Lab vencen cada ~4 h y no deben ir al `.env`.
  IMDSv2 con `HttpPutResponseHopLimit=2` permite que un contenedor llegue a la metadata.
- Tabla nueva `pedidos360-auditoria` (PK `entidad`, SK `ocurridoEn#messageId`, on-demand, TTL
  `expiraEn` a 90 días), escrita por el consumidor `grupo-auditoria-dynamo`.

| Tabla | PK / SK | Escribe |
|---|---|---|
| `HistorialEventos` | `usuarioId` / `instanteEvento` | consumidor de `interacciones-user` |
| `RecomendacionesUsuario` | `usuarioId` | motor de recomendaciones |
| `EventosOperaciones-desarrollo` | `tipo` / `ocurridoEn` (stream, TTL) | snapshot de métricas |
| `pedidos360-auditoria` | `entidad` / `ocurridoEn` (TTL) | `auditoria-eventos` (Kafka) |

Lectura: `GET :8084/api/auditoria?limite=20` y `GET :8084/api/auditoria/{idPedido|usuarioId}`.

### Por qué no se migró TODO MySQL a DynamoDB

Persistencia políglota, a propósito:

- **MySQL** para lo transaccional y relacional: reserva de stock (`@Transactional` con bloqueo de
  fila), pedidos con ítems y pagos. Necesita joins, unicidad y atomicidad entre filas.
- **DynamoDB** para eventos de alto volumen con acceso por clave: historial de interacciones,
  recomendaciones precalculadas, métricas y auditoría. Son escrituras append-only, se consultan
  por PK y crecen sin límite. Ahí DynamoDB escala sin administrar servidores y el TTL las limpia.
