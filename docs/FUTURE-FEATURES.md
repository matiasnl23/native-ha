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

## 2. Actualización remota de la app (sin Play Store)

| Forma | ¿Hay que tocar la tablet? | Requisitos |
|---|---|---|
| La app se actualiza sola, **sin Device Owner** | **Sí**: Android muestra "¿Instalar actualización?" y alguien toca *Instalar*. | Permiso "Instalar apps desconocidas" (se concede una vez desde Ajustes). |
| La app se actualiza sola, **con Device Owner** | **No**: instalación silenciosa. | Device Owner provisionado. |
| Android 12+ sin Device Owner (la app es su propio instalador) | No. | Android 12+: **la tablet con Android 10 no puede**. |
| `adb install -r` por Wi-Fi desde una PC | No. | Depuración inalámbrica; en Android 10 se habilita por USB y se pierde al reiniciar. Solo para desarrollo. |

**Integración propuesta:**
1. **Build de release firmada** con clave privada fuera del repo (Android solo acepta actualizaciones
   con la misma firma). Pasar de la build de debug a la de release implica reinstalar **una vez**.
2. **Publicar el APK** en GitHub Releases (el repo es público) o en la carpeta `www` de Home Assistant.
3. **Botón "Buscar actualización" en HA** (entidad MQTT): la app consulta un archivo de versión,
   descarga el APK, verifica su hash y lanza la instalación.
4. **Seguridad:** la URL de actualización se configura en la app, nunca llega por MQTT; Android además
   rechaza APKs con otra firma.

Conviene hacerlo junto con la build de release y Device Owner (sección 3), para reinstalar una sola vez.

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

### Detalles menores pendientes
- Al volver a la app con una cámara abierta, la vista puede intentar conectar antes de que se reconecte
  el WebSocket y mostrar "Sin video en vivo" (hay que tocar Reintentar).
- La opción "volver a la vista principal por inactividad" se guarda al instante y Cancelar no la revierte;
  su timer sigue corriendo con una cámara abierta.
- El panel de alarma se cierra cuando HA acepta el comando, sin esperar a que salga de "Armando…".
- El receptor de batería queda registrado aunque el control remoto esté desactivado.
