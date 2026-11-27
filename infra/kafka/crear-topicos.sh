#!/usr/bin/env bash
# =============================================================================
#  Pedidos360 — creación de los tópicos Kafka (fuente única: PLAN-INTEGRACION-EV3 §2.1)
#  DSY1107 · Evaluación 3 · criterio 7: configuración DISTINTA por flujo, justificada.
#
#  Lo ejecuta el contenedor `crear-topicos` del compose (imagen
#  confluentinc/cp-kafka:7.5.0, la misma que los brokers, que trae
#  `kafka-topics`). También se puede correr a mano:
#
#      docker compose run --rm -v "$PWD/infra/kafka:/scripts:ro" crear-topicos \
#          bash /scripts/crear-topicos.sh
#      BOOTSTRAP=localhost:9092 ./infra/kafka/crear-topicos.sh   # con el CLI local
#
#  Idempotente: --if-not-exists hace que correrlo dos veces (o `compose up`
#  repetido) no falle. OJO: --if-not-exists NO modifica un tópico que ya existe;
#  si cambia la configuración de un tópico existente, se ajusta con
#  `kafka-configs --alter` (o con ms-kafka-admin, PUT de configuración).
#
#  Valores comunes a TODOS los tópicos (y por qué):
#    replication-factor 3   el clúster tiene 3 brokers: cada partición tiene una
#                           copia en cada broker, así que se puede perder uno
#                           sin perder datos.
#    min.insync.replicas 2  con acks=all (productores, §3.1) una escritura se
#                           confirma recién cuando la tienen 2 réplicas. Con 1
#                           broker caído se sigue escribiendo; con 2 caídos el
#                           productor recibe error en vez de aceptar un mensaje
#                           que vive en un solo disco (durabilidad > disponibilidad).
# =============================================================================
set -euo pipefail

# kafka1:29092 es el listener INTERNO de la red del compose (el 9092 es el que
# se publica al host). Sobrescribible para correrlo desde fuera del compose.
BOOTSTRAP="${BOOTSTRAP:-kafka1:29092}"

# Constantes con nombre: que el número no aparezca suelto en cada tópico.
readonly RF=3
readonly MIN_ISR=2
readonly SIETE_DIAS_MS=604800000      #  7 d
readonly CATORCE_DIAS_MS=1209600000   # 14 d
readonly TREINTA_DIAS_MS=2592000000   # 30 d
readonly NOVENTA_DIAS_MS=7776000000   # 90 d
# 256 MiB por segmento (el default es 1 GiB). La retención y la compactación
# trabajan por segmento CERRADO: con segmentos más chicos, lo vencido se borra
# antes y el disco de un broker de demo (contenedor) no crece de más.
readonly SEGMENTO_BYTES=268435456

# crear <tópico> <particiones> [--config clave=valor ...]
crear() {
  local topico="$1" particiones="$2"
  shift 2
  echo ">> ${topico}: ${particiones} particiones, RF ${RF}, min.insync.replicas ${MIN_ISR} $*"
  kafka-topics --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists \
    --topic "${topico}" \
    --partitions "${particiones}" \
    --replication-factor "${RF}" \
    --config "min.insync.replicas=${MIN_ISR}" \
    "$@"
}

# crear_dlt <tópico original> <particiones del original>
# DeadLetterPublishingRecoverer (§3.4) publica el mensaje muerto en la MISMA
# partición que tenía en el original: el DLT necesita al menos las mismas
# particiones, o la publicación falla para las particiones que no existen.
# 14 d: tiempo de sobra para que alguien revise el error, corrija y
# reprocese; después de eso el mensaje ya no tiene valor operativo.
crear_dlt() {
  local origen="$1" particiones="$2"
  crear "${origen}.DLT" "${particiones}" \
    --config "retention.ms=${CATORCE_DIAS_MS}" \
    --config cleanup.policy=delete \
    --config "segment.bytes=${SEGMENTO_BYTES}"
}

echo "=== Creando tópicos de Pedidos360 en ${BOOTSTRAP} ==="

# --- interacciones-user (key: usuarioId) -------------------------------------
# El flujo de MAYOR volumen (clickstream de la tienda: ver producto, carrito,
# cupones, envío, compra).
#  6 particiones: el paralelismo máximo de un consumer group es el número de
#    particiones; con 6, grupo-recomendaciones (concurrency 3) reparte 2 por
#    hilo y queda margen para escalar hasta 6 consumidores sin re-particionar.
#    La key usuarioId mantiene en orden los eventos de cada usuario.
#  7 d: suficiente para reprocesar la analítica de la última semana (replay)
#    sin guardar indefinidamente datos de comportamiento.
#  lz4: eventos JSON pequeños y muy repetitivos; comprimir en el tópico baja
#    disco y red con el códec más barato en CPU (el mismo que usa el productor).
crear interacciones-user 6 \
  --config "retention.ms=${SIETE_DIAS_MS}" \
  --config cleanup.policy=delete \
  --config compression.type=lz4 \
  --config "segment.bytes=${SEGMENTO_BYTES}"

# --- payment-attempts (key: huellaTarjeta) -----------------------------------
# Intentos de pago que evalúa el antifraude (más de 3 con la misma tarjeta en 10 s).
#  3 particiones: volumen moderado; una por broker reparte el liderazgo.
#    La key huellaTarjeta (SHA-256, nunca el número) garantiza que todos los
#    intentos de una tarjeta caen en la misma partición y se leen EN ORDEN,
#    que es lo que la ventana de 10 s necesita.
#  30 d: auditoría antifraude; permite investigar un reclamo o contracargo del
#    último mes.
crear payment-attempts 3 \
  --config "retention.ms=${TREINTA_DIAS_MS}" \
  --config cleanup.policy=delete \
  --config "segment.bytes=${SEGMENTO_BYTES}"

# --- pagos-resultado (key: idOrden) ------------------------------------------
# Resultado de cada pago (AUTORIZADO / RECHAZADO / ...); ms-carrito lo consume
# para mover el pedido de estado (coreografía).
#  3 particiones: volumen bajo; la key idOrden mantiene en orden los eventos
#    de una misma orden.
#  90 d: es el registro de VENTAS; se conserva más que cualquier otro flujo
#    (conciliación y reportes del trimestre).
crear pagos-resultado 3 \
  --config "retention.ms=${NOVENTA_DIAS_MS}" \
  --config cleanup.policy=delete \
  --config "segment.bytes=${SEGMENTO_BYTES}"

# --- catalogo-productos (key: productoId) -------------------------------------
# ESTADO, no historia: cada mensaje es el snapshot completo de un producto y
# sólo importa el último de cada productoId (value null = producto borrado).
#  compact: Kafka conserva al menos el último valor de cada key; un consumidor
#    nuevo (grupo-catalogo-recomendaciones) reconstruye el catálogo completo
#    leyendo desde el inicio, sin depender de la base de ms-productos.
#  retention.ms=-1: nunca se borra por antigüedad; un producto que no cambia
#    hace un año sigue siendo parte del catálogo.
#  min.compaction.lag.ms=60000: un snapshot recién escrito no se compacta
#    durante 1 min, para que los consumidores en vivo lo alcancen a leer.
#  segment.ms=3600000: el segmento activo NUNCA se compacta; cerrarlo cada
#    hora (en vez de por tamaño, que con un catálogo chico no se alcanzaría
#    nunca) hace que la compactación ocurra de verdad y sea visible en la demo.
#  min.cleanable.dirty.ratio=0.1: compactar cuando el 10 % del log son
#    versiones viejas (default 50 %): el tópico se mantiene cerca de "un
#    mensaje por producto".
#  3 particiones: catálogo chico; la key productoId lleva todas las versiones
#    de un producto a la misma partición, condición para que compactar funcione.
crear catalogo-productos 3 \
  --config cleanup.policy=compact \
  --config retention.ms=-1 \
  --config min.compaction.lag.ms=60000 \
  --config segment.ms=3600000 \
  --config min.cleanable.dirty.ratio=0.1

# --- auditoria-eventos (key: idPedido / usuarioId) ---------------------------
# Puente RabbitMQ -> Kafka: ms-notificaciones reenvía acá cada mensaje de la
# cola notificaciones.auditoria (binding "#", copia de todo el exchange).
# RabbitMQ entrega y borra; Kafka conserva y permite releer (replay) y que
# varios grupos lo consuman: grupo-auditoria-dynamo (ms-recomendaciones) lo
# persiste en la tabla DynamoDB pedidos360-auditoria.
#  3 particiones: volumen bajo (un evento por notificación); la key idPedido
#    deja todos los eventos de un pedido en la misma partición, en orden.
#  90 d: misma retención que el registro de ventas; la auditoría tiene que
#    cubrir al menos el trimestre que se concilia.
#  lz4: JSON repetitivo, comprime bien con poco CPU.
crear auditoria-eventos 3 \
  --config "retention.ms=${NOVENTA_DIAS_MS}" \
  --config cleanup.policy=delete \
  --config compression.type=lz4 \
  --config "segment.bytes=${SEGMENTO_BYTES}"

# --- DLT: una por tópico, con las MISMAS particiones que el original ----------
crear_dlt interacciones-user 6
crear_dlt payment-attempts 3
crear_dlt pagos-resultado 3
crear_dlt catalogo-productos 3
crear_dlt auditoria-eventos 3

# --- Evidencia: configuración efectiva de cada tópico -------------------------
# --describe muestra particiones, líder, réplicas e ISR de cada partición y
# las configuraciones que difieren del default del broker: es lo que prueba
# que cada flujo quedó configurado distinto.
echo
echo "=== Tópicos de Pedidos360 (describe) ==="
for t in interacciones-user payment-attempts pagos-resultado catalogo-productos auditoria-eventos; do
  kafka-topics --bootstrap-server "${BOOTSTRAP}" --describe --topic "${t}"
  kafka-topics --bootstrap-server "${BOOTSTRAP}" --describe --topic "${t}.DLT"
done
