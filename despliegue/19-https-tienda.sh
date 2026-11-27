#!/usr/bin/env bash
# =============================================================================
#  19-https-tienda.sh — la tienda tambien por HTTPS, para el login con Microsoft
#
#  EL PROBLEMA
#  La tienda tiene un boton "Iniciar sesion con Microsoft" (Azure Entra ID,
#  MSAL.js). Azure solo acepta redirect URIs HTTPS fuera de localhost, y MSAL
#  necesita crypto.subtle, que el navegador solo expone en un contexto seguro.
#  Desde http://IP:8091 el login no puede funcionar, ni configurandolo.
#
#  LA SOLUCION (para la demo)
#  Un certificado AUTOFIRMADO para la IP publica, montado en el contenedor de
#  la tienda. El entrypoint de la tienda ve tienda.crt + tienda.key en
#  /etc/nginx/ssl y abre un server 443 identico al de 80. Se publica en 8443.
#  El navegador muestra "conexion no segura" una vez: hay que aceptarlo.
#
#  EN PRODUCCION esto no se hace: va un dominio y un certificado de una CA
#  (ACM detras de un ALB o CloudFront). Una IP sin dominio no puede tener uno.
#
#  Que hace, en orden (idempotente):
#    1. certificado para la IP en /opt/pedidos360/ssl (lo rehace si la IP cambio)
#    2. TIENDA_SSL_DIR en el .env de la EC2
#    3. abre 8443 en el grupo de seguridad
#    4. recrea la tienda (puerto 443 y volumen) y los ms con CORS_ORIGENES,
#       que ahora incluye https://IP:8443
#
#  Lo que queda a mano: agregar https://IP:8443 como redirect URI SPA en
#  Azure Portal -> Registros de aplicaciones -> Pedidos360 -> Autenticacion.
#
#  Requiere: credenciales del Learner Lab vigentes, la EC2 corriendo y el
#  compose con PUERTO_TIENDA_HTTPS / TIENDA_SSL_DIR (rama p4-cognito-identidad).
# =============================================================================
set -uo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
# shellcheck disable=SC1090
[ -f "$ESTADO" ] && . "$ESTADO"
REGION="${REGION:-us-east-1}"
PUERTO_HTTPS="${PUERTO_TIENDA_HTTPS:-8443}"
SSL_DIR=/opt/pedidos360/ssl
export AWS_PAGER=""

sincr(){ tr -d '\r'; }
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
err(){ printf '    \033[1;31mFALLA\033[0m %s\n' "$*"; }

# ------------------------------------------------------------------ instancia
if [ -z "${INSTANCIA:-}" ]; then
  INSTANCIA=$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=pedidos360-ev3" "Name=instance-state-name,Values=running" \
    --query 'Reservations[0].Instances[0].InstanceId' --output text | sincr)
fi
[ -n "$INSTANCIA" ] && [ "$INSTANCIA" != "None" ] || { err "no hay instancia pedidos360-ev3 corriendo"; exit 1; }
IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text | sincr)
SG=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
  --query 'Reservations[0].Instances[0].SecurityGroups[0].GroupId' --output text | sincr)
ok "instancia $INSTANCIA  IP $IP  SG $SG"

# Ejecuta un script en la EC2 por SSM y muestra la salida.
en_ec2(){
  local tmo="$1" script="$2" b64 cid estado
  b64=$(printf 'export HOME=/root PATH=/usr/local/bin:/usr/bin:/bin:$PATH\n%s' "$script" | base64 -w0)
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCIA" \
        --document-name AWS-RunShellScript \
        --parameters "commands=echo $b64 | base64 -d | bash" \
        --timeout-seconds "$tmo" --query 'Command.CommandId' --output text | sincr)
  for _ in $(seq 1 $(( tmo / 5 + 6 ))); do
    sleep 5
    estado=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
              --instance-id "$INSTANCIA" --query Status --output text 2>/dev/null | sincr)
    case "$estado" in Success|Failed|TimedOut|Cancelled) break ;; esac
  done
  aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$INSTANCIA" \
    --query 'StandardOutputContent' --output text 2>/dev/null | sincr | sed 's/^/    /'
  [ "$estado" = "Success" ]
}

# ------------------------------------------------------------------ 1 y 2
log "Certificado autofirmado para $IP y TIENDA_SSL_DIR en el .env"
en_ec2 120 "
set -e
mkdir -p $SSL_DIR
# Se rehace si no existe o si es de otra IP (la IP publica cambia si se pierde la elastica).
if ! openssl x509 -in $SSL_DIR/tienda.crt -noout -ext subjectAltName 2>/dev/null | grep -q 'IP Address:$IP\$'; then
  openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout $SSL_DIR/tienda.key -out $SSL_DIR/tienda.crt \
    -subj '/CN=$IP/O=Pedidos360 (demo, autofirmado)' -addext 'subjectAltName=IP:$IP' 2>/dev/null
  echo 'certificado nuevo'
else
  echo 'certificado vigente para $IP'
fi
# nginx corre como root al leer la llave: 600 basta y no la expone a otros usuarios.
chmod 600 $SSL_DIR/tienda.key; chmod 644 $SSL_DIR/tienda.crt
openssl x509 -in $SSL_DIR/tienda.crt -noout -subject -enddate
cd /opt/pedidos360/cloud-ev3-kafka
grep -q '^TIENDA_SSL_DIR=' .env && sed -i 's#^TIENDA_SSL_DIR=.*#TIENDA_SSL_DIR=$SSL_DIR#' .env || echo 'TIENDA_SSL_DIR=$SSL_DIR' >> .env
grep -q '^PUERTO_TIENDA_HTTPS=' .env || echo 'PUERTO_TIENDA_HTTPS=$PUERTO_HTTPS' >> .env
grep -E '^(TIENDA_SSL_DIR|PUERTO_TIENDA_HTTPS)=' .env
" && ok "certificado y .env" || { err "certificado"; exit 1; }

# ------------------------------------------------------------------ 3
log "Puerto $PUERTO_HTTPS abierto en $SG"
if aws ec2 describe-security-groups --region "$REGION" --group-ids "$SG" \
     --query "SecurityGroups[0].IpPermissions[?FromPort==\`$PUERTO_HTTPS\`] | length(@)" --output text | sincr | grep -q '^0$'; then
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=$PUERTO_HTTPS,ToPort=$PUERTO_HTTPS,IpRanges=[{CidrIp=0.0.0.0/0,Description=tienda-https-login-microsoft}]" >/dev/null \
    && ok "regla creada" || { err "no se pudo abrir $PUERTO_HTTPS"; exit 1; }
else
  ok "ya estaba abierto"
fi

# ------------------------------------------------------------------ 4
log "Recreando la tienda y los ms que leen CORS_ORIGENES"
en_ec2 600 "
set -e
cd /opt/pedidos360/cloud-ev3-kafka
# Solo los servicios cuya configuracion declara CORS_ORIGENES (el ancla spring-env).
MS=\$(docker compose config --format json | python3 -c 'import json,sys; d=json.load(sys.stdin)[\"services\"]; print(\" \".join(s for s,v in d.items() if \"CORS_ORIGENES\" in (v.get(\"environment\") or {})))')
echo \"ms con CORS: \$MS\"
docker compose up -d --no-deps --no-build \$MS tienda 2>&1 | tail -15
docker logs tienda 2>&1 | grep entrypoint | grep -i https || true
" && ok "recreados" || { err "recreacion"; exit 1; }

log "Listo"
echo "    https://$IP:$PUERTO_HTTPS/ingresar   (aceptar el aviso del certificado autofirmado)"
echo "    Falta a mano: redirect URI SPA https://$IP:$PUERTO_HTTPS en Azure Portal -> Pedidos360 -> Autenticacion"
