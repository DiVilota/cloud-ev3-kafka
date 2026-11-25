#!/usr/bin/env bash
# =============================================================================
#  01-infra-ev3.sh — FASE 1 del despliegue de la EV3 en AWS (arquitectura nueva)
#
#  Crea lo que NO depende del codigo: security group + una EC2 con Docker,
#  Compose y git listos. No clona nada ni levanta servicios: eso es 02.
#
#  USO
#     1. Abrir el lab, Start Lab, esperar el circulo verde:
#        https://awsacademy.instructure.com/courses/183051/modules/items/18057489
#     2. Pegar el bloque de "AWS Details" en ~/.aws/credentials
#     3. ./01-infra-ev3.sh
#
#  ES IDEMPOTENTE: si algo existe lo reutiliza. Se puede re-correr tras un fallo.
#
#  POR QUE t3.large Y NO t3.medium: la arquitectura nueva son 22 contenedores
#  (3 Zookeeper + 3 Kafka + 3 RabbitMQ + MySQL + DynamoDB Local + 7 micro-
#  servicios + 2 frontends + Kafka UI + crear-topicos). Medidos en local
#  (doc 17): ~4,6 GB en reposo, mas el pico de los builds de Maven. t3.medium
#  (4 GB) muere por OOM a mitad del arranque; t3.large son 8 GB.
#
#  POR QUE 40 GB DE DISCO: las 9 imagenes propias, las imagenes base (Kafka,
#  RabbitMQ, MySQL, node) y la cache de Maven del build no caben en los 30 GB
#  que usaba el despliegue de tres servicios.
# =============================================================================
set -euo pipefail

REGION="${REGION:-us-east-1}"
NOMBRE_INSTANCIA="pedidos360-ev3"
NOMBRE_SG="grupo-pedidos360"
TIPO="${TIPO:-t3.large}"
DISCO_GB="${DISCO_GB:-40}"
PERFIL_IAM="LabInstanceProfile"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
export AWS_PAGER=""

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
avi(){ printf '    \033[1;33m!!\033[0m %s\n' "$*"; }
die(){ printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- credenciales
log "Comprobando credenciales con una llamada real"
# get-caller-identity responde aun con credenciales canceladas por el lab: no sirve.
aws ec2 describe-vpcs --region "$REGION" --max-items 1 >/dev/null 2>&1 \
  || die "credenciales invalidas o lab detenido. Vuelve a pegar el bloque de AWS Details."
CUENTA=$(aws sts get-caller-identity --query Account --output text)
ok "cuenta $CUENTA, region $REGION"

# --------------------------------------------------------------------- acceso
# P2 pide abrir los puertos SOLO a la IP del equipo. ACCESO=0.0.0.0/0 los abre a
# todo el mundo (util si la demo se ve desde otra red); nunca dejarlo asi.
if [ -z "${ACCESO:-}" ]; then
  MI_IP=$(curl -s --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]' || true)
  [ -n "$MI_IP" ] || die "no pude averiguar tu IP publica; pasa ACCESO=x.x.x.x/32"
  ACCESO="${MI_IP}/32"
fi
ok "los puertos se abriran a $ACCESO"

# ------------------------------------------------------------------ red
log "Descubriendo VPC y subred por defecto"
VPC=$(aws ec2 describe-vpcs --region "$REGION" --filters Name=isDefault,Values=true \
        --query 'Vpcs[0].VpcId' --output text)
[ "$VPC" != "None" ] || die "no hay VPC por defecto en $REGION"

# No vale tomar "la primera subred por defecto": no todas las zonas ofrecen todos
# los tipos. us-east-1e, por ejemplo, no tiene t3.large, y RunInstances falla con
# "Unsupported" despues de haber creado ya el security group. Se pregunta a AWS
# que zonas ofrecen $TIPO y se elige una subred que este en alguna de ellas.
log "Buscando una zona que ofrezca $TIPO"
ZONAS_OK=$(aws ec2 describe-instance-type-offerings --region "$REGION" \
            --location-type availability-zone \
            --filters "Name=instance-type,Values=$TIPO" \
            --query 'InstanceTypeOfferings[].Location' --output text)
[ -n "$ZONAS_OK" ] || die "$TIPO no se ofrece en ninguna zona de $REGION"
ok "zonas con $TIPO: $(echo "$ZONAS_OK" | tr '\t' ' ')"

SUBNET=""
for Z in $ZONAS_OK; do
  SUBNET=$(aws ec2 describe-subnets --region "$REGION" \
            --filters Name=vpc-id,Values="$VPC" Name=availability-zone,Values="$Z" \
            --query 'Subnets[0].SubnetId' --output text 2>/dev/null || echo None)
  if [ "$SUBNET" != "None" ] && [ -n "$SUBNET" ]; then ZONA="$Z"; break; fi
  SUBNET=""
done
[ -n "$SUBNET" ] || die "ninguna subred de $VPC esta en una zona que ofrezca $TIPO"
ok "VPC $VPC / subred $SUBNET (zona $ZONA)"

# ------------------------------------------------------- security group
log "Security group $NOMBRE_SG"
SG=$(aws ec2 describe-security-groups --region "$REGION" \
      --filters Name=group-name,Values="$NOMBRE_SG" Name=vpc-id,Values="$VPC" \
      --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo None)

if [ "$SG" = "None" ] || [ -z "$SG" ]; then
  SG=$(aws ec2 create-security-group --region "$REGION" --group-name "$NOMBRE_SG" \
        --description "Pedidos360 EV3: Kafka, RabbitMQ, 7 microservicios y 2 frontends" \
        --vpc-id "$VPC" --query GroupId --output text)
  ok "creado $SG"
else
  ok "ya existia $SG"
fi

# Puertos. SIN SSH (22): se entra por SSM, que no necesita par de claves ni
# exponer el puerto. Es mas seguro y el lab no deja crear keypairs comodamente.
abrir(){  # puerto_desde, puerto_hasta, descripcion
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=$1,ToPort=$2,IpRanges=[{CidrIp=$ACCESO,Description='$3'}]" \
    >/dev/null 2>&1 && ok "abierto $1-$2 ($3)" || avi "$1-$2 ya estaba abierto para $ACCESO"
}
abrir 8080 8080   "kafka ui"
abrir 8083 8091   "7 microservicios + consola (8090) + tienda (8091)"
abrir 9092 9094   "brokers kafka (clientes fuera de docker)"
abrir 15672 15674 "rabbitmq management (3 nodos)"

# ------------------------------------------------------------------ EC2
log "Instancia EC2 $NOMBRE_INSTANCIA ($TIPO)"
ID=$(aws ec2 describe-instances --region "$REGION" \
      --filters Name=tag:Name,Values="$NOMBRE_INSTANCIA" \
                Name=instance-state-name,Values=pending,running,stopping,stopped \
      --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || echo None)

if [ "$ID" = "None" ] || [ -z "$ID" ]; then
  AMI=$(aws ssm get-parameters --region "$REGION" \
         --names /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
         --query 'Parameters[0].Value' --output text)
  ok "AMI $AMI"

  # user-data: deja Docker, Compose y git listos antes de que entremos por SSM.
  # Ya NO se instala Java: los microservicios corren dentro de contenedores,
  # igual que en local (doc 17). Una sola manera de levantar el sistema.
  USERDATA=$(cat <<'UD'
#!/bin/bash
set -x
dnf update -y
dnf install -y docker git
systemctl enable --now docker
usermod -aG docker ec2-user
# Compose v2 y buildx como plugins del CLI. Versiones FIJAS: con "latest" el
# despliegue depende de lo que se publique hoy (un Compose nuevo empezo a exigir
# buildx 0.17+, que AL2023 no trae, y rompio el build a mitad del arranque).
mkdir -p /usr/local/lib/docker/cli-plugins
curl -sSL "https://github.com/docker/compose/releases/download/v2.31.0/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
curl -sSL "https://github.com/docker/buildx/releases/download/v0.19.3/buildx-v0.19.3.linux-amd64" \
  -o /usr/local/lib/docker/cli-plugins/docker-buildx
chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx
touch /var/lib/cloud/instance/PEDIDOS360_LISTO
UD
)
  ID=$(aws ec2 run-instances --region "$REGION" --image-id "$AMI" \
        --instance-type "$TIPO" --subnet-id "$SUBNET" --security-group-ids "$SG" \
        --iam-instance-profile Name="$PERFIL_IAM" \
        --block-device-mappings "DeviceName=/dev/xvda,Ebs={VolumeSize=$DISCO_GB,VolumeType=gp3}" \
        --metadata-options "HttpTokens=required,HttpEndpoint=enabled" \
        --user-data "$USERDATA" \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NOMBRE_INSTANCIA}]" \
        --query 'Instances[0].InstanceId' --output text)
  ok "creada $ID"
else
  ok "ya existia $ID"
  EST=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$ID" \
         --query 'Reservations[0].Instances[0].State.Name' --output text)
  if [ "$EST" = "stopped" ]; then
    aws ec2 start-instances --region "$REGION" --instance-ids "$ID" >/dev/null
    ok "estaba apagada, arrancando"
  fi
fi

log "Esperando que la instancia este corriendo"
aws ec2 wait instance-running --region "$REGION" --instance-ids "$ID"
IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$ID" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
ok "corriendo, IP publica $IP"

# El agente SSM tarda en registrarse y 02 depende de el: se espera aqui.
log "Esperando que el agente SSM reporte la instancia (puede tardar ~2 min)"
EN_LINEA=None
for _ in $(seq 1 40); do
  EN_LINEA=$(aws ssm describe-instance-information --region "$REGION" \
    --filters "Key=InstanceIds,Values=$ID" \
    --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || echo None)
  [ "$EN_LINEA" = "Online" ] && break
  sleep 15
done
[ "$EN_LINEA" = "Online" ] || die "el agente SSM no reporto. Revisa que el perfil $PERFIL_IAM exista."
ok "SSM en linea"

# ------------------------------------------------------------------ estado
cat > "$ESTADO" <<EOF
# Generado por 01-infra-ev3.sh el $(date -Is)
# La IP cambia en CADA arranque de la instancia: no la escribas en ningun codigo.
CUENTA=$CUENTA
REGION=$REGION
VPC=$VPC
SUBNET=$SUBNET
SG=$SG
INSTANCIA=$ID
IP=$IP
ACCESO=$ACCESO
EOF
ok "estado guardado en $ESTADO"

log "FASE 1 lista"
cat <<EOF
    instancia : $ID   ($TIPO, 8 GB RAM, ${DISCO_GB} GB de disco)
    IP        : $IP
    acceso    : $ACCESO
    SSM       : aws ssm start-session --target $ID --region $REGION

    Sigue:  ./02-sistema-ev3.sh

    Apagar al terminar de trabajar (esto es lo que consume credito):
      aws ec2 stop-instances --region $REGION --instance-ids $ID
EOF
