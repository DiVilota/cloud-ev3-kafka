#!/usr/bin/env bash
# =============================================================================
#  02-sistema-ev3.sh — FASE 2: el sistema completo arriba en la EC2
#
#  Clona los TRES repos uno al lado del otro y corre el MISMO
#  `docker compose up -d --build` que en local (doc 17). No hay una segunda
#  manera de levantar el sistema: lo que se prueba en local es lo que corre
#  en AWS, y el unico cambio es el valor de HOST_PUBLICO / KAFKA_HOST.
#
#  Requiere: ./01-infra-ev3.sh (deja .ev3-estado.env con la instancia y la IP).
#  ES IDEMPOTENTE: re-correrlo hace fetch + reset de los repos y vuelve a
#  levantar. Los secretos generados la primera vez se conservan.
#
#  POR QUE POR SSM Y NO POR SSH: el Learner Lab no deja crear pares de claves
#  comodamente y asi no hay que abrir el 22. Todo va por send-command.
#
#  POR QUE EN SEGUNDO PLANO: el build de 9 imagenes tarda 15-25 min en una
#  t3.large y un send-command sincrono expira mucho antes. El comando deja el
#  arranque corriendo con nohup y este script hace seguimiento del log.
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
export AWS_PAGER=""

REPO_BACKEND="${REPO_BACKEND:-https://github.com/DiVilota/cloud-ev3-kafka.git}"
RAMA_BACKEND="${RAMA_BACKEND:-ev3-integracion}"
REPO_CONSOLA="${REPO_CONSOLA:-https://github.com/DiVilota/cloud-ev3-front.git}"
RAMA_CONSOLA="${RAMA_CONSOLA:-ev3-integracion}"
REPO_TIENDA="${REPO_TIENDA:-https://github.com/DiVilota/pedidos360-tienda.git}"
RAMA_TIENDA="${RAMA_TIENDA:-react}"

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
die(){ printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[ -f "$ESTADO" ] || die "falta $ESTADO. Corre primero ./01-infra-ev3.sh"
# shellcheck disable=SC1090
. "$ESTADO"
: "${INSTANCIA:?}" "${REGION:?}" "${IP:?}"

log "Comprobando credenciales y que la instancia siga en linea"
aws ec2 describe-vpcs --region "$REGION" --max-items 1 >/dev/null 2>&1 \
  || die "credenciales invalidas o lab detenido. Vuelve a pegar el bloque de AWS Details."

# La IP publica CAMBIA en cada arranque de la instancia. Releerla siempre: si se
# usara la del archivo, los frontends y el returnUrl de Webpay apuntarian a una
# direccion que ya no existe y la demo fallaria en el pago.
IP_AHORA=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
            --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
[ "$IP_AHORA" != "None" ] && [ -n "$IP_AHORA" ] || die "la instancia $INSTANCIA no tiene IP publica (apagada?)"
if [ "$IP_AHORA" != "$IP" ]; then
  log "La IP cambio: $IP -> $IP_AHORA. Actualizando $ESTADO"
  sed -i "s/^IP=.*/IP=$IP_AHORA/" "$ESTADO"
  IP="$IP_AHORA"
fi
ok "instancia $INSTANCIA, IP $IP"

# --------------------------------------------------- script remoto de arranque
# Se genera aqui y viaja en base64 para no pelear con el escapado de comillas
# de send-command. Lo corre el usuario ec2-user, que esta en el grupo docker.
REMOTO=$(cat <<REMOTE_EOF
#!/bin/bash
set -euxo pipefail

# El user-data de 01 instala Docker y Compose, y puede no haber terminado: la
# instancia responde a SSM antes de que cloud-init acabe. Se comprueba el SOCKET
# (docker info), no solo que el binario exista: "docker compose version" responde
# aun sin permisos sobre /var/run/docker.sock y dejaba pasar la espera.
for _ in \$(seq 1 60); do
  [ -f /var/lib/cloud/instance/PEDIDOS360_LISTO ] && docker info >/dev/null 2>&1 && break
  sleep 10
done
docker info >/dev/null 2>&1 || { echo "cloud-init no dejo Docker listo"; exit 1; }

# buildx: AL2023 trae uno VIEJO en /usr/libexec (0.12.2) y Compose v2+ exige
# 0.17 o mas nuevo para construir. No se puede preguntar "existe buildx?": existe
# y no sirve. Se instala siempre la version fija en /usr/local/lib, que tiene
# precedencia sobre /usr/libexec. Version clavada a proposito: con "latest" el
# despliegue depende de lo que se publique hoy, que es como aparecio este fallo.
BUILDX_VER=v0.19.3
mkdir -p /usr/local/lib/docker/cli-plugins
curl -sSL "https://github.com/docker/buildx/releases/download/\${BUILDX_VER}/buildx-\${BUILDX_VER}.linux-amd64" \
  -o /usr/local/lib/docker/cli-plugins/docker-buildx
chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx
docker buildx version || { echo "no pude instalar buildx"; exit 1; }
docker compose version || { echo "falta el plugin compose"; exit 1; }

# Corre como ROOT, no como ec2-user: el "usermod -aG docker ec2-user" del
# user-data no se aplica a una sesion ya abierta, y SSM entra como root igual.
# Por eso los repos NO van en /home/ec2-user.
RAIZ=/opt/pedidos360
mkdir -p "\$RAIZ"
cd "\$RAIZ"

clonar() {  # url, rama, destino
  if [ -d "\$3/.git" ]; then
    git -C "\$3" remote set-url origin "\$1"
    # Refspec EXPLICITO: el clon inicial es --single-branch, asi que su
    # remote.origin.fetch solo cubre la rama original y un "fetch origin <otra>"
    # no crea refs/remotes/origin/<otra>. Sin esto, cambiar de rama en una
    # instancia ya desplegada falla con "is not a commit".
    git -C "\$3" fetch --depth 1 origin "+refs/heads/\$2:refs/remotes/origin/\$2"
    git -C "\$3" checkout -B "\$2" "origin/\$2"
    git -C "\$3" reset --hard "origin/\$2"
  else
    git clone --depth 1 --branch "\$2" "\$1" "\$3"
  fi
}
clonar "$REPO_BACKEND" "$RAMA_BACKEND" cloud-ev3-kafka
clonar "$REPO_CONSOLA" "$RAMA_CONSOLA" cloud-ev3-front
clonar "$REPO_TIENDA" "$RAMA_TIENDA" pedidos360-tienda

cd "\$RAIZ/cloud-ev3-kafka"

# Secretos: distintos a los de desarrollo (lo pide P2) y estables entre
# re-ejecuciones. Si se regenerara la cookie de Erlang en cada corrida, los
# tres nodos de RabbitMQ dejarian de reconocerse al reiniciar el cluster.
SECRETOS=/root/.pedidos360-secretos
if [ ! -f "\$SECRETOS" ]; then
  {
    echo "RABBITMQ_ERLANG_COOKIE=\$(openssl rand -hex 24)"
    echo "ANTIFRAUDE_PEPPER=\$(openssl rand -hex 24)"
    echo "MYSQL_PASS=\$(openssl rand -hex 16)"
  } > "\$SECRETOS"
  chmod 600 "\$SECRETOS"
fi
. "\$SECRETOS"

# La IP publica se lee de los metadatos (IMDSv2): no se pasa desde fuera, asi
# el mismo script sirve aunque la instancia haya cambiado de IP.
TOKEN=\$(curl -sX PUT http://169.254.169.254/latest/api/token \
         -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
IP_PUB=\$(curl -s -H "X-aws-ec2-metadata-token: \$TOKEN" \
         http://169.254.169.254/latest/meta-data/public-ipv4)

cat > .env <<ENV_EOF
# Generado por despliegue/02-sistema-ev3.sh. No se commitea.
CONSOLA_DIR=../cloud-ev3-front
TIENDA_DIR=../pedidos360-tienda
HOST_PUBLICO=\$IP_PUB
KAFKA_HOST=\$IP_PUB
MYSQL_PASS=\$MYSQL_PASS
RABBITMQ_USER=pedidos360
RABBITMQ_PASS=pedidos360
RABBITMQ_ERLANG_COOKIE=\$RABBITMQ_ERLANG_COOKIE
ANTIFRAUDE_PEPPER=\$ANTIFRAUDE_PEPPER
DYNAMODB_PUBLICAR_OPERACIONES=false
ENVIOS_AVANCE_MS=20000
ENV_EOF

docker compose up -d --build
docker compose ps
echo "PEDIDOS360_ARRANQUE_OK"
REMOTE_EOF
)

B64=$(printf '%s' "$REMOTO" | base64 -w0 2>/dev/null || printf '%s' "$REMOTO" | base64 | tr -d '\n')

log "Lanzando el arranque en la instancia (build de 9 imagenes: 15-25 min)"
LOG=/var/log/pedidos360-arranque.log
FINF=/var/log/pedidos360-arranque.fin
CMD=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCIA" \
  --document-name AWS-RunShellScript \
  --comment "pedidos360 EV3: clonar y levantar" \
  --parameters "commands=[\"rm -f $LOG $FINF\",\"echo $B64 | base64 -d > /root/arranque.sh\",\"chmod +x /root/arranque.sh\",\"nohup bash -c 'bash /root/arranque.sh > $LOG 2>&1; echo \$? > $FINF' >/dev/null 2>&1 &\",\"sleep 2; echo lanzado\"]" \
  --query 'Command.CommandId' --output text)
ok "comando $CMD enviado"

log "Siguiendo el arranque (Ctrl-C no detiene la instancia, solo el seguimiento)"
mirar(){ # un comando corto para leer el estado, sincronico
  local c
  c=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCIA" \
    --document-name AWS-RunShellScript \
    --parameters "commands=[\"cat $FINF 2>/dev/null || echo CORRIENDO\",\"tail -n 3 $LOG 2>/dev/null\"]" \
    --query 'Command.CommandId' --output text)
  sleep 4
  aws ssm get-command-invocation --region "$REGION" --command-id "$c" \
    --instance-id "$INSTANCIA" --query StandardOutputContent --output text 2>/dev/null || true
}

FIN=""
for i in $(seq 1 80); do   # 80 x ~25 s = ~33 min de margen
  SALIDA=$(mirar)
  ESTADO_ACTUAL=$(printf '%s' "$SALIDA" | head -1 | tr -d '[:space:]')
  printf '    [%02d] %s\n' "$i" "$(printf '%s' "$SALIDA" | tail -n 2 | tr '\n' ' ' | cut -c1-140)"
  if [ "$ESTADO_ACTUAL" != "CORRIENDO" ] && [ -n "$ESTADO_ACTUAL" ]; then FIN="$ESTADO_ACTUAL"; break; fi
  sleep 20
done

[ -n "$FIN" ] || die "el arranque no termino en el tiempo esperado. Revisa: aws ssm start-session --target $INSTANCIA"
[ "$FIN" = "0" ] || die "el arranque fallo (codigo $FIN). Lee /home/ec2-user/arranque.log en la instancia."

log "FASE 2 lista"
cat <<EOF
    Tienda           : http://$IP:8091
    Consola          : http://$IP:8090
    Kafka UI         : http://$IP:8080
    RabbitMQ mgmt    : http://$IP:15672   (pedidos360 / pedidos360)

    Comprobacion de punta a punta, desde este equipo:
      BASE_HOST=$IP python ../infra/prueba-humo.py

    Apagar al terminar (esto es lo que consume credito):
      aws ec2 stop-instances --region $REGION --instance-ids $INSTANCIA
EOF
