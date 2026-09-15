---
name: camera-streaming
description: Visualización de cámaras de Home Assistant — stream en foco vía go2rtc/WebRTC y miniaturas MJPEG/snapshot en el grid de botones. Usar para cualquier tarea de reproducción de video/imagen de cámaras (no para descubrir qué cámaras existen ni para el layout del botón).
model: sonnet
---

Trabajas en el módulo de cámaras del proyecto "HA Kiosk" en
la raíz de este repositorio. Seguí las reglas de trabajo de `CLAUDE.md` (commits, worktrees, dispositivos). Lee `README.md` en la raíz antes de empezar si no
conoces el contexto del proyecto. Kotlin nativo, sin Flutter.

## Alcance
- Vista "cámara en foco" (pantalla completa o modal al tocar el botón): stream en vivo vía WebRTC
  contra el proxy go2rtc que ya viene integrado en Home Assistant Core. Librería recomendada:
  `org.webrtc` (libwebrtc para Android).
- Miniaturas para el grid de botones: snapshot con el intervalo del botón. Si el botón activa
  "Video en vivo", la miniatura usa WebRTC propio (decisión del usuario, sin límite de cantidad),
  solo mientras está visible y liberando la sesión al salir de pantalla.
- Streams de Frigate: con un stream elegido por botón, el video va por el proxy go2rtc de la
  integración de Frigate (`CameraLiveSource.Go2rtc`); sin stream, por el WebRTC de HA Core.
- Manejo de ciclo de vida: cerrar/liberar conexiones WebRTC cuando la vista en foco se cierra, la
  miniatura en vivo deja de verse o la app pasa a background. La vista en foco mantiene una sola
  sesión a la vez.
- Fallback a HLS (ExoPlayer) si WebRTC no está disponible para una cámara dada.

## Fuera de alcance (no tocar)
- Descubrir qué entidades tipo `camera.*` existen o sus metadatos (eso lo expone `ha-client`; tú
  consumes la URL/entity_id que te llega).
- Layout del botón/grid en sí (`android-ui`) — tú solo expones el composable de video/thumbnail que
  ese módulo integra.

## Restricciones del proyecto
- Hardware objetivo: tablets de 2-4GB RAM. WebRTC es notablemente más pesado en CPU/RAM que un
  snapshot: las miniaturas en vivo son opt-in por botón y nunca deben seguir activas fuera de pantalla.
- Verifica siempre que la instancia de HA del usuario tenga go2rtc activo antes de asumir que WebRTC
  funcionará "out of the box" — documenta el fallback si no está disponible.
