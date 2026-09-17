# HA Kiosk

App Android nativa tipo dashboard kiosk para tablets de bajos recursos (2-4GB RAM), que muestra
botones configurables basados en entidades de Home Assistant, con estado en tiempo real, vista de
cámaras y control remoto desde HA. No es un reemplazo de la Companion App oficial: es un dashboard
de uso propio, pensado para hardware limitado.

## Decisiones de arquitectura

| Área | Decisión | Por qué |
|---|---|---|
| Lenguaje/UI | Kotlin + Jetpack Compose (nativo, sin Flutter) | En benchmarks comparables, Flutter usa ~5x más RAM que Compose (~955MB vs ~184MB). En tablets de 2GB ese margen es crítico. Además, Device Owner/reboot/brillo/pantalla son APIs 100% Android — con Flutter se necesitaría igualmente código Kotlin vía platform channels, sin ahorro real. |
| Auth con HA | Long-Lived Access Token | Se genera desde el perfil de usuario en HA. Bearer en REST, mensaje `auth` en WebSocket. |
| Tiempo real | WebSocket API de HA implementado a mano sobre OkHttp | No existe SDK oficial maduro para Kotlin/Android; el protocolo es simple (`subscribe_events` / `state_changed`) y es el mismo patrón que usa la Companion App oficial (open source, sirve de referencia). |
| Cámaras (foco) | go2rtc + WebRTC | Ya viene integrado en HA Core. Latencia ~0.3-0.5s. Solo una conexión activa a la vez para no saturar RAM en tablets de gama baja. Cada botón puede elegir un stream de Frigate (main, sub u otro de go2rtc); en ese caso el video va por el proxy go2rtc de la integración de Frigate. |
| Cámaras (miniaturas en grid) | Snapshot (o WebRTC opcional) | Snapshot con intervalo configurable por botón: mucho más liviano que varias conexiones WebRTC. Opcionalmente, un botón muestra la miniatura en vivo (sin límite de cantidad, solo mientras está visible); conviene un stream sub. |
| Control remoto (pantalla, tema, timeout) | Cliente MQTT + MQTT Discovery | La tablet se publica como dispositivo con entidades propias en HA (mismo patrón que Fully Kiosk Browser). Ya hay broker Mosquitto corriendo en la instancia de HA del usuario. |
| Reinicio remoto / modo kiosko estricto | Device Owner (opcional) | Requiere provisionar el dispositivo una vez vía `adb shell dpm set-device-owner` (sin root, pero exige el dispositivo sin cuenta Google configurada). La app detecta en runtime si tiene privilegios de Device Owner y habilita/oculta esas funciones según corresponda — nunca es un requisito duro. |
| minSdk / targetSdk | minSdk 26 (Android 8.0) | Para poder reutilizar celulares/tablets viejos como dispositivos de prueba o kiosko. targetSdk al compileSdk más reciente disponible. |

Referencias usadas en la investigación: protocolo MQTT de Fully Kiosk Browser (patrón de qué
entidades exponer), proyectos `kiosk-satellite` y `FreeKiosk` (patrones de Device Owner/lockdown),
`home-assistant-js-websocket` (referencia canónica del protocolo WebSocket de HA).

## Roadmap

1. **MVP1** ✅ — Auth con token, cliente WebSocket, dashboard de botones configurable por el usuario.
   Probado contra una instancia real de HA por https (Let's Encrypt).
2. **MVP2** ✅ — Cámaras: vista en foco vía go2rtc/WebRTC + miniaturas por snapshot en el grid.
   - **Rediseño del dashboard** ✅ — modo edición, tiles redimensionables, drag & drop, múltiples
     vistas y botones inteligentes (luces, alarma). Ver [`docs/UI-REWORK.md`](docs/UI-REWORK.md).
3. **MVP3** — Cliente MQTT + Discovery para control remoto desde HA. Ver
   [`docs/MVP3-MQTT.md`](docs/MVP3-MQTT.md).
4. **Producción** — Build de release firmada en GitHub Actions, distribución por GitHub Releases y
   actualizaciones automáticas configurables (más disparo manual desde HA). Ver
   [`docs/RELEASE-OTA.md`](docs/RELEASE-OTA.md).

Ideas evaluadas para más adelante (apagado real de pantalla, Device Owner y otras mejoras):
[`docs/FUTURE-FEATURES.md`](docs/FUTURE-FEATURES.md).

## Entorno de desarrollo

Se puede desarrollar desde macOS o Linux: instalar Android Studio, clonar y abrir. Los pasos
(incluyendo `adb`, JDK para `./gradlew` y emuladores en Apple Silicon) están en
[`docs/SETUP.md`](docs/SETUP.md).

## Testing

Dispositivos de prueba disponibles: un celular Android, una tablet Android 10 y el emulador de
Android Studio. `adb` está en `platform-tools` dentro del SDK (`~/Library/Android/sdk` en macOS,
`~/Android/Sdk` en Linux) — sirve para desplegar builds sin Android Studio y para el paso de
provisioning de Device Owner (`adb shell dpm set-device-owner`).

Build y tests desde la terminal: `./gradlew assembleDebug testDebugUnitTest`.

## Agentes de este proyecto

Ver `.claude/agents/` — hay un agente dedicado por frente de trabajo (UI/dashboard, cliente HA,
streaming de cámaras, control del dispositivo). Cada uno conoce las decisiones de este README y su
alcance específico.
