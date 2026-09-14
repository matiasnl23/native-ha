# MVP3 — Control remoto de la tablet desde Home Assistant (MQTT)

La tablet se publica en Home Assistant como un **dispositivo MQTT** (MQTT Discovery), con entidades
para controlarla y sensores con su estado, igual que hace Fully Kiosk Browser. Se conecta al broker
Mosquitto que ya corre en la instancia de HA.

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Alcance | Pantalla (encender/apagar, brillo, apagado por inactividad), vista del dashboard, abrir cámara por comando, sensores de la tablet y recargar la app. |
| Device Owner | **Después**, en un paso aparte junto con la build de release firmada (apagado real de pantalla, reinicio remoto, modo kiosko estricto). |
| Configuración del broker | Desde la app: host, puerto, usuario, contraseña (cifrada como el token de HA) y TLS opcional. Nunca en el código. |
| Conexión 24/7 | Servicio en primer plano con notificación persistente, reconexión automática y LWT de disponibilidad. |
| Brillo | Brillo de la ventana de la app (no requiere permisos; la app de kiosko está siempre en primer plano). |
| "Apagar" pantalla sin Device Owner | Capa negra a pantalla completa + brillo mínimo; un toque o el comando de encender la vuelve atrás. El apagado real queda para Device Owner. |
| Apagado por inactividad | Lo maneja la app (no el timeout del sistema): tras N minutos sin toques se "apaga" la pantalla como arriba; 0 = nunca. |
| Librería MQTT | Se elige verificando que esté mantenida (sin el `MqttAndroidClient` de Paho, abandonado). |
| Protocolo de HA | Topics, esquemas de Discovery y disponibilidad verificados contra el código fuente de HA. |

## Entidades en Home Assistant

| Entidad | Tipo | Uso |
|---|---|---|
| Pantalla | `switch` | Encender / "apagar" la pantalla (p. ej. encender al detectar presencia). |
| Brillo | `number` 0–100 % | Brillo de la app. |
| Apagar pantalla tras | `number` (minutos, 0 = nunca) | Apagado por inactividad. |
| Vista | `select` | Cambiar la vista del dashboard (opciones = vistas configuradas). |
| Volver a la vista principal | `button` | |
| Cámara en pantalla | `select` | Abrir una cámara de las que están en el dashboard, o "Ninguna" para cerrarla. |
| Cerrar cámara tras | `number` (segundos, 0 = no cerrar sola) | Cierre automático al abrir por comando (p. ej. timbre). |
| Recargar | `button` | Reconecta con HA y recarga el dashboard. |
| Batería | `sensor` % | |
| Cargando | `binary_sensor` | |
| Vista actual | `sensor` | |
| Última interacción | `sensor` timestamp | Útil para automatizaciones por presencia. |
| Disponibilidad | LWT | La tablet aparece "no disponible" si se desconecta. |

Además se documenta un topic de comando JSON para automatizaciones (p. ej. abrir una cámara con un
tiempo de cierre específico).

## Topics y comandos

Discovery **por componente** (`homeassistant/<component>/<deviceId>/<objectId>/config`), no discovery
de dispositivo (`homeassistant/device/.../config` con `cmps`): con discovery por componente cada
entidad se republica sola cuando cambia (por ejemplo, las opciones de "Vista" al agregar una vista),
mientras que con discovery de dispositivo habría que reenviar el payload completo de las 12 entidades
en cada cambio. Verificado contra el código fuente de Home Assistant (rama `dev` de
[`home-assistant/core`](https://github.com/home-assistant/core), paquete `homeassistant/components/mqtt/`:
`schemas.py`, `switch.py`, `number.py`, `select.py`, `button.py`, `sensor.py`, `binary_sensor.py`,
`discovery.py`, `abbreviations.py`) y contra la [documentación de MQTT Discovery](https://www.home-assistant.io/integrations/mqtt/).
Claves completas (no abreviadas): `state_topic`, `command_topic`, `unique_id`, `device`, `origin`,
`availability_topic`, `device_class`, `unit_of_measurement`, `options`, `min`/`max`/`step`/`mode`,
`payload_on`/`payload_off`/`payload_press`. Ninguno de estos esquemas cambió en los últimos años;
versión mínima de HA recomendada: **2023.8** (bloque `origin`, ya estable para entonces).

`<deviceId>` es el id de 16 hex de la instalación (ver `MqttDeviceIdProvider`). Topics propios de la
tablet (definidos en `MqttTopics`):

| Uso | Topic |
|---|---|
| Disponibilidad (etapa 1) | `hakiosk/<deviceId>/availability` (`online`/`offline`, retenido) |
| Estado de una entidad | `hakiosk/<deviceId>/<objectId>/state` (retenido) |
| Comando de una entidad | `hakiosk/<deviceId>/<objectId>/set` |
| Comando JSON | `hakiosk/<deviceId>/command` |
| Discovery config | `homeassistant/<component>/<deviceId>/<objectId>/config` (retenido) |

`<objectId>` por entidad: `screen` (switch), `brightness` y `screen_off_timeout` (number),
`view` (select), `main_view` (button), `camera` y `camera_close_after` (number), `reload` (button),
`battery` (sensor), `charging` (binary_sensor), `current_view` y `last_interaction` (sensor).

Las vistas y cámaras pueden repetir nombre: el `select` de Home Assistant sólo maneja strings, así que
los nombres duplicados se desambiguan agregando " (2)", " (3)"... en el orden en que aparecen
(`OptionCatalog`). Ese mismo mapeo nombre→id se usa para traducir la opción elegida de vuelta a un
`RemoteCommand`; si el nombre ya no existe (vista borrada, cámara quitada del dashboard), el comando se
ignora. Cuando cambian las vistas o cámaras del dashboard, sólo se republica el `config` del `select`
afectado (retenido), no el resto de las entidades.

### Comando JSON (`hakiosk/<deviceId>/command`)

Para automatizaciones que no quieran manejar los topics `.../set` de cada entidad. Payload malformado
o comando desconocido se ignora (se loguea sin volcar el payload). Un `close_after` presente pero no
numérico se trata como ausente (usa el valor de "Cerrar cámara tras") en vez de descartar todo el
comando.

```json
{"command": "open_camera", "entity_id": "camera.timbre", "close_after": 30}
{"command": "close_camera"}
{"command": "show_view", "view": "Cocina"}
{"command": "main_view"}
{"command": "screen", "on": true}
{"command": "brightness", "value": 40}
{"command": "reload"}
```

`show_view` acepta el id de la vista o su nombre (tal como se ve en el `select` "Vista"). Los valores
numéricos (`brightness`, `close_after`) se clampean al rango de la entidad correspondiente.

Ejemplos de automatización en Home Assistant:

```yaml
# Abrir la cámara del timbre 30 s cuando suena
automation:
  - alias: "Tablet: mostrar timbre"
    trigger:
      - platform: state
        entity_id: binary_sensor.timbre
        to: "on"
    action:
      - service: mqtt.publish
        data:
          topic: "hakiosk/<deviceId>/command"
          payload: '{"command":"open_camera","entity_id":"camera.timbre","close_after":30}'

  # Encender la pantalla al detectar movimiento
  - alias: "Tablet: pantalla con movimiento"
    trigger:
      - platform: state
        entity_id: binary_sensor.movimiento_pasillo
        to: "on"
    action:
      - service: mqtt.publish
        data:
          topic: "hakiosk/<deviceId>/command"
          payload: '{"command":"screen","on":true}'
```

(La misma acción de encender pantalla también puede hacerse activando el `switch.pantalla` de la
entidad Discovery, sin pasar por MQTT a mano.)

### Publicación y ciclo de vida

`RemoteControlPublisher` corre mientras el proceso vive (arrancado junto con `MqttConnectionManager`
desde `DeviceModule`), independiente de la actividad. Al conectar (o reconectar) publica todo el
discovery y todos los estados retenidos; entre reconexiones sólo publica los estados que cambiaron,
coalescidos con un debounce de 500 ms para no generar ráfagas. La batería/carga se lee de
`ACTION_BATTERY_CHANGED` (sticky, sin permisos). Si el usuario borra la configuración del broker, no se
intenta borrar las entidades: el dispositivo queda "no disponible" por el LWT y el usuario puede
borrarlo a mano desde Home Assistant.

## Etapas

### Etapa 0 — Contrato (coordinador)
`data/device/`: `MqttConfig`/`MqttConfigStore`, `MqttRemoteControl` (conexión y prueba),
`RemoteCommand` (lo que HA pide), `DeviceUiState` (lo que la UI reporta) y `RemoteControlBridge`
(puente entre la capa MQTT y la UI), con fakes.

### Etapa 1 — Conexión MQTT (`device-control`, Opus)
Librería verificada, config store cifrado, cliente con reconexión y LWT, servicio en primer plano,
prueba de conexión. En paralelo con la etapa 3.

### Etapa 2 — Discovery y estado (`device-control`)
Publicación de las entidades de la tabla, suscripción a comandos → `RemoteControlBridge`, publicación
del estado (desde `DeviceUiState` y batería/carga), limpieza de entidades obsoletas.

### Etapa 3 — UI (`android-ui`)
Pantalla de configuración del broker; aplicar comandos (capa de pantalla apagada, brillo, apagado por
inactividad, cambiar vista, abrir/cerrar cámara con cierre automático, recargar); reportar
`DeviceUiState`. En paralelo con la etapa 1 contra los fakes.

### Etapa 4 — Integración y prueba en la tablet (usuario)

### Después — Release firmada + Device Owner
