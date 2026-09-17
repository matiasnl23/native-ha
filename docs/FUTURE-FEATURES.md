# Posibles features a futuro

Ideas evaluadas pero todavía no planificadas. Cada una resume qué se sabe hoy, las opciones y sus
pros/contras, para poder retomarla sin volver a investigar desde cero. Antes de implementar cualquiera,
verificar los detalles de Android contra la documentación oficial y probarlos en la tablet (Android 10).

## 1. Apagado real de pantalla (sin Device Owner)

**Hoy:** la entidad "Pantalla" de HA pone una capa negra por encima de todo con brillo mínimo. El
display queda físicamente encendido (consumo, retroiluminación visible de noche en LCD, posible marcado
en OLED), pero un "encender" desde HA siempre funciona.

**Cómo lo hace Fully Kiosk sin instalación especial:**

| Enfoque | Pros | Contras |
|---|---|---|
| **Administrador de dispositivo (Device Admin) + `lockNow()`** | Apagado real, como el botón de encendido. Se concede desde un diálogo de Ajustes, sin adb. | Bloquea el dispositivo: si hay PIN, al despertar aparece la pantalla de bloqueo. Para desinstalar la app hay que quitarle antes el permiso de administrador. |
| **Timeout del sistema** (permiso "Modificar ajustes del sistema") | Apagado real sin bloquear. Un solo permiso desde Ajustes. | Cambia un ajuste global de Android (hay que guardarlo y restaurarlo). No es instantáneo: tarda el timeout mínimo (~10–15 s). |
| **Capa negra** (actual) | Sin permisos, despierta al instante, funciona en cualquier tablet. | No apaga el display. |

**Encender desde HA con la pantalla apagada:** *wake lock* que despierta el display y traer la app al
frente. En Android 10+ abrir una actividad desde segundo plano está restringido; Fully pide **"Mostrar
sobre otras apps"** para poder hacerlo.

**Brillo global:** con "Modificar ajustes del sistema" se puede cambiar el brillo de Android (hoy solo
se cambia el brillo de la ventana de la app).

**Propuesta:** apagado con Device Admin si el permiso está concedido y capa negra como respaldo;
encendido con wake lock + "Mostrar sobre otras apps"; brillo global opcional; en la pantalla de
control remoto, indicar qué permisos faltan con botones que abran cada ajuste.

**A verificar en la tablet:** comportamiento de `lockNow()` con y sin PIN, y los permisos para abrir la
app desde segundo plano (varían según versión de Android y fabricante).

## 2. Actualización remota de la app (sin Play Store) — ya planificada

Dejó de ser una idea: el plan por etapas (build firmada en GitHub Actions, distribución por GitHub
Releases, chequeo automático configurable y entidad `update` en Home Assistant) está en
[`RELEASE-OTA.md`](RELEASE-OTA.md), con los detalles de Android verificados contra la documentación
oficial y AOSP.

## 3. Device Owner (opcional)

Solo hace falta para lo que ningún permiso común habilita:
- **Reinicio remoto** de la tablet desde HA.
- **Modo kiosko estricto** (fijar la app, sin poder salir).
- **Actualizaciones silenciosas** y conceder permisos sin preguntar.

**Requisitos / contras:** provisionar con `adb shell dpm set-device-owner` en una tablet **sin cuentas**
(o reseteada de fábrica); build de release firmada; la app es más difícil de desinstalar. Pendiente
saber si la tablet de kiosko tiene una cuenta de Google configurada.

## 4. Otras ideas y mejoras anotadas

- **Arranque automático** al encender la tablet (`BOOT_COMPLETED`); hoy hay que abrir la app.
- **Más tipos de botón inteligente:** persianas (posición), clima (temperatura y modo), ventilador
  (velocidad), reproductores multimedia. Cada uno es una entrada en `DomainTileBehaviors`.
- **Botón existente como link a una vista** con doble toque o mantener presionado (fuera del modo
  edición; en modo edición mantener presionado arrastra).
- **APK más chico:** dividir por arquitectura (ABI splits o App Bundle); hoy incluye libwebrtc para las
  cuatro arquitecturas.
- **Cámaras sin WebRTC:** fallback con HLS (hoy se muestran capturas periódicas).
- **Conexión MQTT más robusta:** wake lock / Wi-Fi lock para que Doze no corte la conexión; publicar el
  estado real aunque Android reinicie la app en segundo plano sin abrir la pantalla.
- **Una sola `PeerConnectionFactory` para las miniaturas en vivo:** hoy cada sesión crea la suya y,
  además, un `JavaAudioDeviceModule` aunque la miniatura sea muda (`receiveAudio = false` solo evita el
  transceiver). Con varias miniaturas en vivo en una misma vista eso es RAM y threads nativos de más en
  una tablet de 2 GB. El `EglBase` ya se comparte; faltan la factory y saltear el ADM sin audio.
- **Liberar los renderers sin bloquear el hilo principal:** `EglRenderer.release()` espera en un latch,
  y al cambiar de vista se libera un renderer por miniatura, secuencialmente, desde el hilo principal.
  Con una sola cámara en foco no se notaba; conviene medirlo en la tablet Android 10 con varias
  miniaturas en vivo antes de darlo por bueno.

### Detalles menores pendientes
- Al volver a la app con una cámara abierta, la vista puede intentar conectar antes de que se reconecte
  el WebSocket y mostrar "Sin video en vivo" (hay que tocar Reintentar).
- La opción "volver a la vista principal por inactividad" se guarda al instante y Cancelar no la revierte;
  su timer sigue corriendo con una cámara abierta.
- El panel de alarma se cierra cuando HA acepta el comando, sin esperar a que salga de "Armando…".
- El receptor de batería queda registrado aunque el control remoto esté desactivado.
- Los campos numéricos de las opciones de cámara aceptan valores que desbordan `Int`; al no poder
  parsearlos se usa el default en silencio, sin avisar que lo tipeado se descartó.
- La lista de streams de go2rtc se vuelve a pedir por HTTP cada vez que se abre el modal de edición,
  sin caché.
- Una cámara con miniatura en vivo que falla sigue pidiendo snapshots a su intervalo y reintentando
  WebRTC cada 30 s indefinidamente: en una cámara caída es tráfico permanente.
