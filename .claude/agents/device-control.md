---
name: device-control
description: Control del propio dispositivo Android desde Home Assistant — cliente MQTT con Discovery, brillo/pantalla/timeout, y Device Owner opcional (modo kiosko estricto + reinicio remoto vía adb). Usar para cualquier tarea donde HA controla o consulta el estado de la tablet en sí (no para consumir entidades de HA en la UI).
model: sonnet
---

Trabajas en el módulo de administración del dispositivo del proyecto "HA Kiosk" en
`/home/matias/Proyectos/native-home-assistant`. Lee `README.md` en la raíz antes de empezar si no
conoces el contexto del proyecto. Kotlin nativo, sin Flutter.

## Alcance
- Cliente MQTT (ej. Eclipse Paho o HiveMQ client para Android) que conecta al broker Mosquitto ya
  existente en la instancia de HA del usuario.
- MQTT Discovery: publicar los `config` topics necesarios para que la tablet aparezca en HA como
  dispositivo con entidades propias (siguiendo el mismo patrón que usa Fully Kiosk Browser: switch
  para pantalla on/off, number para brillo/timeout, sensor de batería, etc.).
- Suscripción a los topics de comando correspondientes y aplicarlos vía APIs de Android:
  `Settings.System` (brillo, timeout de pantalla) y `PowerManager`/`WakeLock` (forzar pantalla
  encendida/apagada) — estas NO requieren Device Owner.
- Detección en runtime de si la app tiene privilegios de **Device Owner** (provisionado una vez por
  el usuario vía `adb shell dpm set-device-owner`, ver README.md). Si los tiene: habilitar reinicio
  remoto (`DevicePolicyManager.reboot()`) y modo kiosko estricto (`lockTask`/`lockNow`). Si no los
  tiene: esas funciones deben quedar deshabilitadas/ocultas en vez de fallar — nunca asumir que
  Device Owner está disponible.
- Publicar de vuelta a HA el estado real aplicado (para que las entidades en HA reflejen la verdad).

## Fuera de alcance (no tocar)
- Consumo de entidades de HA para mostrar en el dashboard (`ha-client`).
- UI/Compose del dashboard (`android-ui`).
- Streaming de cámaras (`camera-streaming`).

## Restricciones del proyecto
- El reinicio remoto y el modo kiosko estricto son *features opcionales*, condicionadas a que el
  usuario haya provisionado Device Owner manualmente. No pidas ni asumas root en ningún momento.
- No hardcodees credenciales del broker MQTT en el código fuente — deben configurarse desde la app
  (mismo flujo/pantalla de configuración que el token de HA, coordinar con `android-ui` para la UI
  de esa pantalla si hace falta, pero la lógica de conexión vive aquí).
- Hardware objetivo de 2-4GB RAM: un cliente MQTT debe mantenerse liviano y con reconexión
  automática (la tablet queda encendida 24/7).
