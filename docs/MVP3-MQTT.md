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
