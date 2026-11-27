#!/usr/bin/env bash
# =============================================================================
#  20-tienda-en-gateway.sh — la URL de la etapa abre la tienda
#
#  QUE PROBLEMA RESUELVE
#  La "URL de invocacion" de la etapa (Consola AWS -> API Gateway -> Etapas ->
#  desarrollo) es https://<api>.execute-api.<region>.amazonaws.com/desarrollo.
#  Abrirla respondia {"message":"Missing Authentication Token"}. Ese mensaje NO
#  es de permisos: API Gateway REST lo devuelve para CUALQUIER ruta o verbo que
#  no esta publicado, y la raiz "/" no tenia ningun metodo (el 14 solo publica
#  /api/* y /salud/*). El docente pide que esa URL lleve al frontend.
#
#  QUE HACE
#    GET /            -> http://IP:8091/          (index.html de la tienda)
#    GET /{proxy+}    -> http://IP:8091/{proxy}   (assets, img, config.js, y
#                                                  las rutas de la SPA, que el
#                                                  nginx resuelve con try_files)
#  Las rutas explicitas (/api/..., /salud/...) le ganan a {proxy+}: API Gateway
#  elige siempre el recurso mas especifico, asi que el contrato del 14/16 no
#  cambia. Tampoco se toca ningun metodo existente (correr el 14 de nuevo
#  pisaria la reescritura de X-Usuario-Id que dejo el 16).
#
#  TRES DETALLES QUE SI O SI HACEN FALTA (integracion HTTP, no proxy)
#   1. Content-Type: sin mapearlo, API Gateway responde todo como
#      application/json y el navegador se niega a ejecutar el JS (los modulos
#      exigen un MIME de JavaScript). Se copia el del nginx.
#   2. Accept-Encoding: identity. Si nginx comprimiera con gzip, el cuerpo
#      binario pasaria por una integracion de texto y llegaria corrupto.
#   3. La tienda tiene que saber que vive en /desarrollo/ y no en la raiz: lo
#      resuelve su index.html (ver pedidos360-tienda/src/lib/base.ts). Con una
#      imagen anterior a ese cambio, esta ruta muestra la pagina en blanco.
#
#  CORS: la tienda servida desde el gateway llama a las API del MISMO origen,
#  pero el navegador igual manda Origin en los POST y Spring lo valida. Por eso
#  hay que agregar el origen del gateway a CORS_ORIGENES (ORIGEN_GATEWAY en el
#  .env, ver docker-compose.yml) y recrear los microservicios.
#
#  Requiere: REGION, IP y API_REST (de .ev3-estado.env o exportadas).
#  Es idempotente.
# =============================================================================
set -uo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
# shellcheck disable=SC1090
[ -f "$ESTADO" ] && . "$ESTADO"
: "${REGION:?falta REGION}" "${IP:?falta IP}" "${API_REST:?falta API_REST}"
API="$API_REST"
STAGE="${STAGE:-desarrollo}"
PUERTO="${PUERTO_TIENDA:-8091}"
export AWS_PAGER=""

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
TMP_URL=$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")   # AWS CLI de Windows
sincr(){ tr -d '\r'; }                                     # CRLF del CLI de Windows
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
err(){ printf '    \033[1;31mFALLA\033[0m %s\n' "$*"; }
declare -i FALLOS=0

# La operacion va ANTES de las opciones: "aws apigateway --rest-api-id X
# get-resources" falla con "Found invalid choice 'X'".
aws_gw(){ local op="$1"; shift; aws apigateway "$op" --region "$REGION" --rest-api-id "$API" "$@"; }

RAIZ=$(aws_gw get-resources --limit 500 --query "items[?path=='/'].id | [0]" --output text | sincr)
PROXY=$(aws_gw get-resources --limit 500 --query "items[?path=='/{proxy+}'].id | [0]" --output text | sincr)
if [ -z "$PROXY" ] || [ "$PROXY" = "None" ]; then
  PROXY=$(aws_gw create-resource --parent-id "$RAIZ" --path-part '{proxy+}' --query id --output text | sincr)
fi

cat > "$TMP/resp-metodo.json" <<'JSON'
{"method.response.header.Content-Type": true, "method.response.header.Cache-Control": true}
JSON
cat > "$TMP/resp-integracion.json" <<'JSON'
{"method.response.header.Content-Type": "integration.response.header.Content-Type",
 "method.response.header.Cache-Control": "integration.response.header.Cache-Control"}
JSON

publicar(){   # $1 id del recurso, $2 uri, $3 etiqueta, $4 con_proxy (0|1)
  local rec="$1" uri="$2" et="$3" conp="$4" pm pi e
  pi="\"integration.request.header.Accept-Encoding\": \"'identity'\""
  pm=""
  if [ "$conp" = 1 ]; then
    pm="\"method.request.path.proxy\": true"
    pi="$pi, \"integration.request.path.proxy\": \"method.request.path.proxy\""
  fi
  printf '{%s}' "$pm" > "$TMP/pm.json"
  printf '{%s}' "$pi" > "$TMP/pi.json"

  if ! e=$(aws_gw put-method --resource-id "$rec" --http-method GET --authorization-type NONE \
             --request-parameters "file://$TMP_URL/pm.json" 2>&1); then
    grep -q ConflictException <<< "$e" || { err "$et put-method: $(head -c 120 <<< "$e")"; FALLOS+=1; return; }
  fi
  if ! e=$(aws_gw put-integration --resource-id "$rec" --http-method GET --type HTTP \
             --integration-http-method GET --uri "$uri" \
             --request-parameters "file://$TMP_URL/pi.json" \
             --passthrough-behavior WHEN_NO_MATCH --timeout-in-millis 29000 2>&1); then
    err "$et put-integration: $(head -c 120 <<< "$e")"; FALLOS+=1; return; fi
  aws_gw put-method-response --resource-id "$rec" --http-method GET --status-code 200 \
    --response-parameters "file://$TMP_URL/resp-metodo.json" >/dev/null 2>&1 || true
  if ! e=$(aws_gw put-integration-response --resource-id "$rec" --http-method GET --status-code 200 \
             --response-parameters "file://$TMP_URL/resp-integracion.json" 2>&1); then
    err "$et integration-response: $(head -c 120 <<< "$e")"; FALLOS+=1; return; fi
  ok "GET $et -> $uri"
}

log "Publicando la tienda en la raiz de la API $API (nginx en $IP:$PUERTO)"
publicar "$RAIZ"  "http://$IP:$PUERTO/"        "/"         0
publicar "$PROXY" "http://$IP:$PUERTO/{proxy}" "/{proxy+}" 1

log "Desplegando en la etapa '$STAGE'"
aws_gw create-deployment --stage-name "$STAGE" --description "tienda en la raiz $(date -Is)" \
  --query id --output text | sed 's/^/    despliegue /'

URL="https://$API.execute-api.$REGION.amazonaws.com/$STAGE"
log "Resultado"
[ "$FALLOS" -gt 0 ] && err "$FALLOS operaciones fallaron" || ok "la tienda queda en $URL/"
cat <<EOF

    En el .env de la EC2 (CORS de los microservicios):
       ORIGEN_GATEWAY=https://$API.execute-api.$REGION.amazonaws.com
    y recrear: docker compose up -d --force-recreate --no-deps \\
       ms-pago ms-recomendaciones ms-kafka-admin ms-productos ms-carrito ms-notificaciones ms-rabbit-admin

    Comprobacion (~30 s de propagacion):
       curl -sD - -o /dev/null "$URL/"                  # 200, text/html
       curl -s "$URL/config.js"                         # la config de la tienda
EOF
