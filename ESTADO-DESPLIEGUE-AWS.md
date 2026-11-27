# Estado del despliegue en AWS — EV3 Pedidos360

> **Este es el lab de respaldo (Ismael, cuenta 471112736485).** El despliegue principal, el que
> se evalúa, está en [`ESTADO-DESPLIEGUE-DIEGO.md`](ESTADO-DESPLIEGUE-DIEGO.md) (cuenta 851725287694).

Ultima actualizacion: 2026-11-23 16:16 (-03)

## Donde esta todo

| | |
|---|---|
| Lab | curso **183051** (item de Canvas 18057489) |
| Cuenta | 471112736485 · us-east-1 |
| Instancia | `i-0bdac4ec3cb93f53c` · **t3.large** (7,8 GB) · disco 50 GB |
| IP | **44.208.92.186** — fija, por Elastic IP: no cambia al apagar y prender |
| API Gateway | `https://khca9i6w62.execute-api.us-east-1.amazonaws.com/desarrollo` |
| Repo backend | commit `95ec1af` en master |

### Puertos

| Servicio | Puerto | Rutas |
|---|---|---|
| ms-pago | 8083 | /api/pagos · /api/alertas |
| ms-recomendaciones | 8084 | /api/recomendaciones · /api/interacciones · /api/metricas · **/ws/metricas** |
| ms-kafka-admin | 8085 | /api/admin |
| ms-productos | 8086 | /api/productos |
| ms-carrito | 8087 | /api/carrito · /api/pedidos · /api/admin/resumen |
| ms-notificaciones | 8088 | /api/notificaciones |
| ms-rabbit-admin | 8089 | /api/rabbit |
| consola | 8090 | front de operaciones |
| tienda | 8091 | front de la tienda |
| kafka-ui | 8080 | interfaz de Kafka |

## Verificado en AWS

- **Prueba de humo**: 25/25 (catalogo, interacciones, recomendaciones, carrito, pedido, pago, notificaciones, antifraude, metricas, administracion).
- **API Gateway**: 22 rutas, una por una, **sin `{proxy+}`**. Compra completa por el gateway.
- **CORS**: `access-control-allow-origin` presente; preflight `OPTIONS` responde 200.
- **DLT**: dos mensajes defectuosos terminan en `pagos-resultado.DLT` con causas distinguidas (`JsonParseException` inmediato, `InvalidFormatException` tras reintento).
- **DynamoDB real** (+2): tablas `HistorialEventos`, `RecomendacionesUsuario` y `EventosOperaciones-desarrollo` (con stream NEW_IMAGE). Sin credenciales en el contenedor: usa el rol de la instancia.
- **WebSocket** (+3): handshake 101 desde el origen autorizado, 403 desde uno ajeno, y snapshots con valores que cambian.
- **P5**: las cifras del panel admin concilian al peso (`categorias + envios == ingresos`) y respetan los 14 dias. 81 pruebas de ms-carrito en verde.

Evidencias en `evidencias/50` a `54`.

## Cosas que hay que saber para no romperlo

1. **Credenciales**: vencen en ~1 hora. Recargar con `~/.hermes/duoc/aws-cred.sh` (lee el portapapeles). Los contenedores NO dependen de ellas: usan el rol de la instancia.
2. **`docker-compose.override.yml`** (en la instancia, no en git) corrige dos cosas del compose base: `CORS_ORIGENES`, que esta fijado a localhost y en AWS rechaza al propio front, y el endpoint de DynamoDB.
3. **Metadata de EC2**: necesita `HttpPutResponseHopLimit` en 2 para ser alcanzable desde un contenedor. Ya esta puesto.
4. **API Gateway**: el valor de una cabecera estatica debe pasarse por **archivo JSON** (`file://`). Con la forma `clave=valor` la CLI se come las comillas simples y rechaza el mapeo.
5. **Propagacion**: tras `create-deployment` el stage tarda hasta un minuto. Un 403 o 500 recien publicado suele ser eso; `test-invoke-method` prueba la configuracion sin pasar por el stage.
6. **Entorno de la instancia**: buildx se actualizo a 0.19.3 (el 0.12 no soporta `compose build`) y se instalo Python 3.11 (la prueba de humo usa `str | None`, que 3.9 no entiende).

## Pendiente

| | Que falta |
|---|---|
| P6 | Evidencia de un pago **Webpay real** aprobado (+5) |
| P4 | Login con Cognito + filtro JWT |
| P3 | Terraform de la Lambda de telemetria — ojo: el lab no deja crear roles IAM, viene `LabRole` dado |
| — | Ensayar la demo y preparar el informe |
