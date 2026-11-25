# `despliegue/` — el sistema de la EV3 en AWS (Learner Lab)

Dos scripts. El primero crea la infraestructura, el segundo levanta el sistema.

```bash
# 1. Start Lab en AWS Academy, esperar el circulo verde
# 2. AWS Details -> AWS CLI -> Show, y pegar el bloque en ~/.aws/credentials
cd despliegue
./01-infra-ev3.sh      # security group + EC2 t3.large con Docker (~3 min)
./02-sistema-ev3.sh    # clona los 3 repos y hace docker compose up --build (~20 min)

# 3. Comprobacion de punta a punta, desde tu equipo
BASE_HOST=<ip> python ../infra/prueba-humo.py     # debe dar TODO OK
```

Al terminar de trabajar, **apagar la instancia**: es lo que consume el credito.

```bash
. despliegue/.ev3-estado.env
aws ec2 stop-instances --region $REGION --instance-ids $INSTANCIA
```

## La decision de fondo: un solo modo de levantar el sistema

El despliegue anterior (scripts `01`–`05`, para la arquitectura de tres servicios)
instalaba Java en la instancia y corria cada microservicio como una unidad
`systemd`, con los topicos creados a mano. Eso significaba **dos maneras distintas**
de levantar lo mismo: Docker Compose en local y systemd en AWS. Cada cambio en la
arquitectura habia que hacerlo dos veces, y lo que se probaba en local no era lo
que corria en la demo.

Ahora la EC2 corre el **mismo `docker-compose.yml`** que la maquina de desarrollo,
el mismo que dejo 22 contenedores arriba y la prueba de humo en 25/25 (doc 17). La
unica diferencia entre local y AWS es el valor de dos variables (`HOST_PUBLICO` y
`KAFKA_HOST`) y que los secretos se generan al azar en la instancia. Los topicos
los crea el servicio `crear-topicos` del compose, que delega en
`infra/kafka/crear-topicos.sh`: 4 topicos + 4 DLT con la configuracion por flujo
del contrato, en vez de los 2 topicos planos que creaba `03-topicos-ev3.sh`.

## Por que las cosas estan como estan

| Decision | Porque |
|---|---|
| `t3.large` (8 GB) | el sistema mide ~4,6 GB en reposo mas el pico de los builds; en `t3.medium` (4 GB) el kernel mata procesos a mitad del arranque |
| 40 GB de disco | 9 imagenes propias + imagenes base (Kafka, RabbitMQ, MySQL, node) + cache de Maven no caben en 30 GB |
| Entrada por **SSM**, sin abrir el 22 | el Learner Lab no deja crear pares de claves comodamente, y asi no hay puerto SSH expuesto |
| El arranque corre con `nohup` y se sigue por log | el build tarda 15-25 min y un `send-command` sincrono expira mucho antes |
| La IP publica se lee de IMDSv2 **dentro** de la instancia | la IP cambia en cada arranque; si se pasara desde fuera, el `returnUrl` de Webpay y las `API_*` de los frontends apuntarian a una direccion muerta |
| Secretos generados en la instancia y guardados en `~/.pedidos360-secretos` | P2 pide que la cookie de Erlang y el pepper del antifraude no sean los de desarrollo, y tienen que sobrevivir a un re-arranque: si la cookie cambia, los 3 nodos de RabbitMQ dejan de reconocerse |
| Puertos abiertos solo a tu IP (`ACCESO`) | el sistema no tiene autenticacion todavia (eso es P4); `ACCESO=0.0.0.0/0` existe para ver la demo desde otra red, nunca para dejarlo asi |
| Los dos scripts son idempotentes | las credenciales del lab caducan cada ~4 h y el lab se reinicia solo: re-correrlos tiene que ser seguro |

## Trampas

- **Las credenciales del Learner Lab caducan cada ~4 h.** Cuando un script falle con
  "credenciales invalidas", vuelve a pegar el bloque de AWS Details. No hay que
  recrear nada: la instancia sigue ahi.
- **La IP publica cambia cada vez que la instancia arranca.** No la escribas en
  ningun archivo: `02-sistema-ev3.sh` la relee y regenera el `.env`. Si apagaste y
  prendiste la instancia, **vuelve a correr `02`** aunque el sistema parezca estar
  bien: los frontends tienen la IP vieja compilada en su configuracion.
- El primer `docker compose up` tarda varios minutos solo en descargar imagenes;
  no esta colgado. El seguimiento imprime las ultimas lineas del log cada ~25 s.
- Un `Exited (0)` en `crear-topicos` es lo esperado: es un contenedor de un solo uso.
- `.ev3-estado.env` y el `.env` de la instancia **no se commitean**.
