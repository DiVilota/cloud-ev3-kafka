# Estado del despliegue en AWS — lab de Diego

Última actualización: **2026-11-26** (sesión de la tarde). Entrega: viernes 27-11.

> ⚠️ **Lee primero "Sesión del 26-11 por la tarde"**, al final: hay una decisión
> abierta sobre **F1** y el despliegue actual NO está como lo dejó la sesión del
> miércoles.

> Hay **dos** despliegues. Este es el de Diego y es el **principal** para la demo.
> El de Ismael (`ESTADO-DESPLIEGUE-AWS.md`, cuenta 471112736485) queda como
> respaldo: tiene créditos pero está apagado y sólo él puede encenderlo.

## Dónde está todo

| | |
|---|---|
| Cuenta | **851725287694** · us-east-1 |
| Instancia | `i-0f5f47b9ffdefa327` · t3.large (7,8 GB) · disco 40 GB · zona us-east-1f |
| IP | **18.211.30.140** — fija, por Elastic IP (`eipalloc-0b27f261dec9c1ed9`) |
| API Gateway | `https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo` |
| Código en la instancia | `/opt/pedidos360/cloud-ev3-kafka`, rama `p2b-gateway-frontend` |
| Rama del trabajo | `DiVilota/cloud-ev3-kafka` → `p2b-gateway-frontend` (PR sin abrir) |

Las URL que se muestran en la demo:

```
Tienda   http://18.211.30.140:8091      Kafka UI  http://18.211.30.140:8080
Consola  http://18.211.30.140:8090      RabbitMQ  http://18.211.30.140:15672
```

## Verificado el 26-11 (todo en AWS, no en local)

| Qué | Resultado |
|---|---|
| `docker compose ps -a` | 21 corriendo + `crear-topicos` Exited(0) |
| `BASE_HOST=18.211.30.140 prueba-humo.py` | **TODO OK** (25 comprobaciones) |
| API Gateway: rutas `/api/*` | **29 declaradas**, responden 200 |
| `config.js` de la tienda | las 5 API apuntan a la URL del gateway |
| CORS por el gateway | `Access-Control-Allow-Origin: http://18.211.30.140:8091` — el origen **real**, no `*`; un origen ajeno no recibe cabeceras |
| Query strings por el gateway | `/api/recomendaciones?userId=…` devuelve el catálogo (antes se perdía el parámetro) |
| Cabecera `X-Usuario-Id` por el gateway | 3 detalles de producto → `interaccionesPorMinuto` 0 → **3**: la cabecera atraviesa, el evento llega a Kafka y la telemetría lo recibe |
| Las 3 colas de RabbitMQ | `evidencias/55`: cliente, auditoría y DLQ con mensajes reales |

## Las observaciones de la EV2, cerradas

| | Observación | Cómo quedó |
|---|---|---|
| F1 | API Gateway para el frontend | ⚠️ **REABIERTO el 26-11 por la tarde** — ver la sección del final. El `.env` ya no define `API_BASE`, así que los dos frontends van **directos** a `IP:PUERTO`. Las 29 rutas del gateway siguen publicadas y respondiendo; lo que cambió es que el navegador ya no las usa |
| F2 | No usar proxy en backend | ningún `proxy_pass`; el navegador llama a la API, no a nginx |
| F3 | CORS con la ruta de la API | el gateway reenvía `Origin` y devuelve la respuesta CORS del servicio, que valida contra su lista explícita |
| F4 | Todas las colas funcionan | `evidencias/55` (RabbitMQ) + `evidencias/51` (DLT de Kafka) |

## Cosas que hay que saber para no romperlo

1. **La Elastic IP no cambia** al apagar y prender. Cuesta ~US$0,12/día mientras
   la instancia está apagada, y a cambio no hay que reapuntar nada.
2. **`API_BASE` y `HOST_PUBLICO`** viven en el `.env` de la instancia (no en git).
   `HOST_PUBLICO` decide CORS, el `returnUrl` de Webpay y el WebSocket;
   `API_BASE` decide por dónde entran los frontends. Vaciar `API_BASE` devuelve
   el sistema al modo desarrollo (un puerto por servicio) sin tocar código.
3. **Dos cosas NO pasan por el gateway, a propósito**: el WebSocket de métricas y
   el stream SSE de notificaciones. Una integración HTTP de API Gateway REST
   bufferea la respuesta, así que un stream no la atraviesa. Van directos.
4. **Una ruta recién publicada da 403 o 500 durante ~1 minuto**: es propagación
   del stage, no un error de configuración. Esperar y reintentar.
5. **El AWS CLI de Windows escribe CRLF.** Un id capturado del CLI con `\r` al
   final rompe la firma SigV4 y AWS responde
   `UnrecognizedClientException: security token invalid`, que no tiene nada que
   ver con las credenciales. Los scripts filtran con `tr -d '\r'`.
6. Los puertos **8083–8089 están abiertos a `0.0.0.0/0`** porque el API Gateway
   no tiene IPs fijas y necesita alcanzarlos. Es una limitación conocida que
   cerraría P4 (Cognito), fuera de alcance. 8090, 8091, 15672 y los brokers
   siguen restringidos a la IP del equipo: **el viernes, desde otra red, hay que
   agregar esa IP al security group** o no cargarán los frontends.

## Qué falta

| | Qué |
|---|---|
| **P1** | Consola alineada al backend nuevo (Diego, en curso) |
| **P6** | Evidencia de un pago Webpay **aprobado** — 10 min, en `http://18.211.30.140:8091` |
| **P7** | Ensayo de la demo y evidencias de fallos (los dos) |
| — | Corregir `evidencias/50`: afirma que 12 rutas `/v1` dan 200 y cinco dan **500** (nunca tuvieron *integration response*). O se corren el `13` y se republica, o se corrige el documento |
| — | Abrir el PR de `p2b-gateway-frontend` hacia `1samadhi/cloud-ev3-kafka` |

## Cómo retomar

```bash
# 1. Start Lab y pegar el bloque de AWS Details en ~/.aws/credentials
#    (sólo [default] y las tres aws_*; si se cuelan AWSAccountId/Region, falla)
# 2. Prender la instancia (la IP NO cambia)
aws ec2 start-instances --region us-east-1 --instance-ids i-0f5f47b9ffdefa327
# 3. Comprobar que el sistema volvió solo (restart: unless-stopped)
BASE_HOST=18.211.30.140 python infra/prueba-humo.py     # debe dar TODO OK
# 4. Al terminar, apagar: es lo que consume crédito
aws ec2 stop-instances --region us-east-1 --instance-ids i-0f5f47b9ffdefa327
```

## Sesión del 26-11 por la tarde

Trabajo hecho desde el notebook. **Todo verificado en AWS con el navegador**, no en local.

### Lo que se arregló

La tienda mostraba *"modo demo · sin backend"* y no se podía comprar. Dos causas
encadenadas:

1. El contenedor `tienda` seguía con `API_BASE` horneado apuntando al gateway.
   Al quitar `API_BASE` del `.env` (sesión anterior) sólo se recreó `consola`;
   `tienda` llevaba una hora corriendo con el valor viejo. **Un `docker compose
   up -d` sin `--force-recreate` no refresca el entorno de un contenedor vivo.**
2. El badge de salud (`src/hooks/useSalud.ts`) sondea `<base>/actuator/health`
   de los 5 servicios. El gateway **no expone `/actuator/health` en ninguna
   ruta**, así que los 5 sondeos fallaban → `vivos === 0` → "modo demo". El
   catálogo sí cargaba: el badge mentía sobre un backend que estaba sano.

Se recreó `tienda` con puertos directos. Verificado en el navegador: badge
**"backend conectado"**, catálogo de 20 productos desde MySQL, agregar al carrito
OK (llega notificación por RabbitMQ), `ms-pago` responde y valida.

También se descubrió que `/api/pagos` existe en el gateway **como recurso sin
métodos** (sólo `/api/pagos/iniciar` tiene POST), así que `GET /api/pagos`
devuelve 403 *Missing Authentication Token*. No es un fallo de credenciales.

### Imágenes del catálogo (commits `957cb3a` y `e1637e9`, y `202a723` en la tienda)

Las 20 filas tenían `imagen_url NULL` y descripción ya poblada. Ahora hay un SVG
ilustrativo por producto en `pedidos360-tienda/public/img/<id>.svg`.

Dos cosas que conviene no deshacer sin querer:

- `imagenUrl` es una **ruta relativa** (`/img/<id>.svg`), que el navegador
  resuelve contra el origen de la tienda. Así no hay nada que regenerar si
  algún día se trabaja sin Elastic IP.
- El lienzo es 800×600 (4/3, el `aspect-ratio` de `.ph`) con el dibujo dentro
  del cuadrado central. El `object-fit: cover` de `.ph--mini` recorta a 1/1, y
  un SVG cuadrado sale recortado en las tarjetas.

**Al desplegar sobre una instancia ya sembrada, el cambio de Java NO basta**:
`SemillaCatalogo.cargarSiVacio()` sólo inserta si la tabla `producto` está
vacía, para no pisar el stock que movieron las reservas. Hay que correr además:

```sql
UPDATE producto SET imagen_url = CONCAT('/img/', id, '.svg');
```

### DECISIÓN ABIERTA · F1 quedó reabierto

Apuntar la tienda a los puertos directos **deshizo el cierre de F1**, que era una
observación del docente. Hoy ningún frontend pasa por el gateway.

Es un choque de diseño, no un bug: con el gateway las 5 bases de `config.js` son
**la misma URL**, así que los 5 sondeos de salud van al mismo endpoint y no se
pueden distinguir. El badge de salud y un gateway de base única son
incompatibles tal como está escrito `useSalud`.

Opciones, de más barata a más fiel:

| | Qué | Costo |
|---|---|---|
| A | Restaurar `API_BASE` y sondear por servicio un endpoint que el gateway **ya** expone (`productos → /api/productos`, `pagos → /api/alertas`, `recomendaciones → /api/metricas`, `notificaciones → /api/notificaciones`, `carrito → /api/carrito`). Cierra F1 y el badge queda verde, pero mide "responde", no salud de Actuator | 30–40 min, sólo tienda |
| B | Restaurar `API_BASE` y publicar 5 rutas `/salud/<servicio>` en el gateway hacia el actuator de cada microservicio. Salud real por servicio | 1,5–2 h, gateway + tienda |
| C | Dejarlo directo y defender F1 con las evidencias de la EV2 ya capturadas | 0 |
| D | Restaurar `API_BASE` y aceptar que el badge diga "modo demo" | 5 min |

Para volver atrás basta con reponer `API_BASE` en el `.env` de la instancia y
recrear **los dos** frontends (no sólo uno):

```bash
cd /opt/pedidos360/cloud-ev3-kafka
echo 'API_BASE=https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo' >> .env
docker compose up -d --force-recreate --no-deps tienda consola
```

### Estado de los repos

| Repo | Rama | Remoto | La instancia |
|---|---|---|---|
| `cloud-ev3-kafka` | `p2b-gateway-frontend` | `e1637e9` | **atrasada**: en `8c6d061`, le faltan `a092aad` (Ismael) + los dos de hoy |
| `pedidos360-tienda` | `react` | `202a723` | en `fc1c6c8` + los SVG copiados a mano |

La instancia funciona, pero su código ya no coincide con el remoto. Antes de
tocar nada conviene `git pull` en ambos y reconstruir lo que corresponda —
traer el commit de Ismael puede requerir rebuild de más de un servicio.

### Pendiente de verificar

El checkout completo. El carrito exige sesión (`src/lib/auth.ts`, cuenta de
cliente `ana@correo.cl`); no se probó el flujo Webpay de punta a punta, que es
justo **P6**.

