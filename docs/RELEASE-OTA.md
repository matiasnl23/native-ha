# Salida a producción: release firmada y actualizaciones OTA

Plan para pasar de "compilo debug y la instalo con `adb`" a un sistema independiente del Play Store:
GitHub Actions compila y firma, publica en **GitHub Releases**, y cada tablet chequea si hay versión
nueva y se actualiza. El chequeo automático es configurable y se puede desactivar; además la
actualización se puede disparar a mano desde Home Assistant.

Complementa [`MVP3-MQTT.md`](MVP3-MQTT.md) (control remoto por MQTT, sobre el que se apoya la etapa 4)
y reemplaza la sección 2 de [`FUTURE-FEATURES.md`](FUTURE-FEATURES.md).

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Dónde se publica | **GitHub Releases** del repo (ya es público). La app lee `https://github.com/matiasnl23/native-ha/releases/latest/download/release-metadata.json`: es una URL estable documentada por GitHub, no pasa por la API y por lo tanto no gasta rate limit. |
| Cuándo compila el CI | Al pushear un tag `v*`, más `workflow_dispatch` para casos manuales. Los pushes a `main` no generan releases. |
| Versionado | Del tag: `v1.4.2` → `versionName = "1.4.2"` y `versionCode = major*10000 + minor*100 + patch` (10402). Una sola fuente de verdad, reproducible. **No** se usa `github.run_number`: se resetea si se renombra el workflow y rompería la monotonía del `versionCode`. |
| Firma | Keystore de release **fuera del repo**, en secrets de GitHub (base64). Se activan explícitamente v2 y v3; con `targetSdk 37` Android exige **mínimo v2**. |
| Instalación en la tablet | API `PackageInstaller` (sesiones). `ACTION_INSTALL_PACKAGE` está deprecado desde API 29 y da errores menos accionables. |
| Interacción del usuario | Sin Device Owner, Android **siempre** muestra el diálogo "¿Instalar actualización?" y alguien lo toca en la tablet. El flag para evitarlo (`setRequireUserAction`) es API 31: la tablet Android 10 no lo tiene. |
| Permiso | `REQUEST_INSTALL_PACKAGES` en el manifest + `canRequestPackageInstalls()` en runtime; si falta, la app abre `ACTION_MANAGE_UNKNOWN_APP_SOURCES` con `package:` propio. Se concede una vez. |
| Verificación antes de instalar | Doble: **SHA-256** del metadata y **certificado de firma** del APK descargado comparado con el de la app instalada. El hash solo prueba que bajaste lo que está publicado; la firma prueba que salió de la clave privada, que nunca estuvo en GitHub. |
| Chequeo automático | Intervalo configurable en horas (0 = desactivado), con jitter, y un chequeo al arrancar la app. Persistido igual que el resto de las preferencias. |
| Entidad en Home Assistant | Entidad **`update`** por MQTT Discovery: muestra versión instalada vs. disponible y trae botón "Instalar". |
| Device Owner | **Fase posterior**, documentada al final. Habilita instalación silenciosa (sin tocar la tablet), pero exige provisionar con `adb` una tablet sin cuentas de Google. |
| Migración a firma de release | Antes de migrar se agrega **export/import de la configuración** (etapa 0): cambiar de clave de firma obliga a desinstalar y eso borra todos los datos de la app. |

## El problema de la migración

Android solo permite actualizar una app si el APK nuevo está firmado con **la misma clave**. Hoy las
tablets tienen la app firmada con `app/debug.keystore`; el primer APK de release va firmado con otra
clave, así que hay que **desinstalar e instalar**, y eso borra la URL de Home Assistant, el token, el
layout del dashboard y la configuración del broker MQTT.

Por eso la etapa 0 es el export/import. Lo que se puede exportar y lo que no:

| Dato | ¿Se exporta? |
|---|---|
| Layout del dashboard (vistas, botones, grillas, opciones de cámara) | Sí. `DashboardLayout` ya es `@Serializable` y `DashboardLayoutJsonMapper` ya tiene formato versionado. |
| URL base de Home Assistant | Sí. |
| Preferencias de pantalla y de vistas (`display_prefs`, `dashboard_view_prefs`) | Sí. |
| Broker MQTT (host, puerto, usuario, TLS, nombre del dispositivo) | Sí, **sin la contraseña**. |
| Token de Home Assistant y contraseña del broker | **No.** Están cifrados con una clave del Android Keystore que no se puede exportar (es la misma razón por la que están excluidos de `backup_rules.xml`). Se vuelven a pegar a mano, una vez. |
| `deviceId` de MQTT | **No.** Debe ser único por instalación; la tablet aparece como dispositivo nuevo en HA. |

Sirve también para clonar la configuración a una tablet nueva, que es la otra mitad del valor.

## Cómo se entera la tablet de que hay versión nueva

El workflow publica, junto al APK, un `release-metadata.json`:

```json
{
  "versionCode": 10402,
  "versionName": "1.4.2",
  "apkUrl": "https://github.com/matiasnl23/native-ha/releases/download/v1.4.2/hakiosk-1.4.2.apk",
  "sha256": "…",
  "sizeBytes": 42123456,
  "minSdk": 26,
  "commit": "…",
  "releaseUrl": "https://github.com/matiasnl23/native-ha/releases/tag/v1.4.2"
}
```

La app compara el `versionCode` del metadata contra el suyo (`PackageInfo.longVersionCode`), no
strings de versión. El flujo completo es: metadata → ¿hay versión mayor? → descargar el APK a
almacenamiento **interno** (`cacheDir`; en externo otra app podría cambiarlo entre la verificación y
la instalación) → verificar SHA-256 → verificar firma → sesión de `PackageInstaller` → `commit()` →
diálogo del sistema → la app se reinicia.

Se usa la URL estable `/releases/latest/download/…` en vez de la API de GitHub a propósito: sin token
la API permite **60 requests por hora y por IP de origen** (compartida por toda la casa detrás del
NAT), y se comprobó que un 304 por ETag **igual consume cuota** cuando no hay autenticación.

## Etapas

### Etapa 0 — Export/import de la configuración
Habilita la migración sin perder el trabajo hecho en el dashboard. Formato JSON versionado (mismo
criterio que `DashboardLayoutJsonMapper`), con validación al importar: un archivo de una versión
futura o corrupto se rechaza sin tocar lo guardado. Import y export desde la pantalla de
configuración, usando el selector de archivos del sistema (Storage Access Framework, sin permisos).
Al importar se avisa explícitamente que el token hay que volver a pegarlo.
- Agente: `android-ui` (**Opus**: toca persistencia real del usuario, es donde más caro sale un error).

### Etapa 1 — Build de release firmada + GitHub Actions ✅
Sin cambios visibles en la app. Verificado de punta a punta con la release **`v1.0.1`**: el workflow
compiló, firmó y publicó el APK, el `release-metadata.json` se lee desde la URL estable
`releases/latest/download/`, y sobre el APK descargado de esa release coinciden el tamaño y el SHA-256
declarados (el mismo chequeo que hará la app) con firma v2+v3 de la clave de release.
- `signingConfigs { create("release") }` leyendo la keystore desde variables de entorno y aplicándose
  solo si existen, para que `./gradlew assembleRelease` siga funcionando localmente sin secrets.
  `enableV2Signing` y `enableV3Signing` explícitos, sin depender del default de AGP.
- `versionCode`/`versionName` por propiedades (`-PversionCode= -PversionName=`), con los valores
  actuales como default.
- Workflow `.github/workflows/release.yml`: `actions/checkout` + `actions/setup-java` +
  `gradle/actions/setup-gradle` (la doc de Gradle desaconseja explícitamente `setup-java` con
  `cache: gradle`, y `gradle-build-action` está archivado), `assembleRelease testDebugUnitTest`
  (AGP solo crea tareas de unit test para el `testBuildType`, que es debug),
  `apksigner verify --print-certs`, generación del `release-metadata.json` y publicación con `gh release
  create --verify-tag --generate-notes` (`gh` viene preinstalado en el runner; una dependencia de
  terceros menos en el job que descifra la keystore).
- El job declara **`environment: PRODUCTION`**: la keystore y sus contraseñas viven en ese environment
  y GitHub solo se los pasa a un job que lo referencie. Sin esa línea el build corre igual y falla al
  firmar con secrets vacíos. Ver [`SETUP.md`](SETUP.md#qué-implica-que-vivan-en-un-environment-y-no-en-el-repo).
- Higiene de la clave: secrets por `env:` del step y nunca interpolados en el `run:`, keystore
  decodificada a `$RUNNER_TEMP` y borrada con `if: always()`, `base64 -w0` (el masking de secrets
  funciona por coincidencia exacta), sin `ACTIONS_STEP_DEBUG`, actions pineadas por SHA, y nunca
  `pull_request_target` en este repo.
- Generación de la keystore y carga de secrets: ver
  [`SETUP.md`](SETUP.md#8-firma-de-release-y-secrets-de-github-actions). **Si se pierde esa clave,
  ninguna tablet puede volver a actualizarse sin desinstalar.**
- Agente: coordinador (toca build y CI, fuera del alcance de los agentes de feature).

### Etapa 2 — Cliente de actualización en la app
Kotlin puro y testeable, sin UI.
- Consulta del metadata, comparación por `versionCode`, descarga con OkHttp (sigue redirects por
  defecto) chequeando espacio libre contra `sizeBytes`, verificación de SHA-256 y de firma.
- Verificación de firma con `getPackageArchiveInfo` pasando **`GET_SIGNATURES or GET_SIGNING_CERTIFICATES`
  juntos**: en Android 9, 10 y 13 AOSP solo recolecta certificados si está `GET_SIGNATURES`, así que
  pasar únicamente el flag moderno devuelve `signingInfo` nulo — justo en la tablet Android 10.
- Sesión de `PackageInstaller` y manejo de estados: `STATUS_PENDING_USER_ACTION` (lanzar el diálogo),
  `STATUS_FAILURE_CONFLICT` (firma incompatible), `STATUS_FAILURE_STORAGE`, `STATUS_FAILURE_ABORTED`.
- Programación del chequeo: corrutina en el proceso de la app (que en un kiosko está siempre vivo) con
  intervalo configurable y jitter. Se evita WorkManager a propósito: sería una dependencia nueva para
  un caso que el propio kiosko ya cubre.
- Receiver de `ACTION_MY_PACKAGE_REPLACED` para levantar el servicio después de actualizar (el proceso
  se mata durante la instalación). Volver a traer la Activity al frente choca con las restricciones de
  inicio de actividades en segundo plano de Android 10: se documenta como limitación y se verifica en
  la tablet, no se promete.
- Tests JVM con `mockwebserver` (ya está en el catálogo de dependencias).
- Agente: `device-control` (**Opus**: seguridad de la verificación y manejo de estados del instalador).

### Etapa 3 — Pantalla de actualizaciones
Colgada de la pantalla de configuración, con el mismo patrón que "Control remoto (MQTT)".
- Versión instalada, versión disponible, botón "Buscar ahora", estado y progreso de descarga.
- Selector de intervalo de chequeo, con opción "Desactivado".
- Si falta el permiso de instalar apps desconocidas, cartel con botón que abre la pantalla de Ajustes
  correspondiente.
- Agente: `android-ui` (Sonnet).

### Etapa 4 — Entidad `update` en Home Assistant
- `DeviceEntityKey.UPDATE("update", "update", "Actualización")` + `DiscoveryPayloads.updateConfig`,
  siguiendo la convención del proyecto de claves completas sin abreviar.
- State en JSON en `hakiosk/<deviceId>/update/state`: `installed_version`, `latest_version`, `title`,
  `release_url`, `release_summary`, `in_progress`, `update_percentage`.
- `command_topic` = `hakiosk/<deviceId>/update/set` y `payload_install` **siempre publicados juntos**:
  el botón "Instalar" de HA aparece con solo tener `command_topic`, pero `payload_install` no tiene
  default en HA y tocarlo sin ese campo falla con `KeyError`.
- El JSON del state no admite claves extra: una sola clave desconocida invalida el mensaje entero.
- Versiones mínimas de Home Assistant: **2022.11** para la plataforma `update` de MQTT, **2024.11** si
  se quiere barra de progreso (`update_percentage`). La documentación oficial dice 2021.11 y está mal;
  verificado contra los tags de `home-assistant/core`.
- Agente: `device-control` (Sonnet).

### Etapa 5 — Migración de las tablets (usuario)
Una sola vez por dispositivo: exportar la configuración → `adb uninstall` → instalar el APK de release
firmado → importar la configuración → pegar el token de HA y la contraseña del broker. A partir de
acá, las actualizaciones son sin desinstalar.

### Después — Device Owner (fase aparte)
Única forma de que la instalación sea **silenciosa** en Android 10: con Device Owner el sistema saltea
el diálogo y ni siquiera emite `STATUS_PENDING_USER_ACTION` (verificado en AOSP: los device owners
tienen la verificación de permiso exenta). No hace falta ninguna API distinta ni whitelist, sigue
siendo `PackageInstaller`. El costo es provisionar con `adb shell dpm set-device-owner` una tablet sin
cuentas de Google. Junto con esto vienen el reinicio remoto y el modo kiosko estricto (ver
[`FUTURE-FEATURES.md`](FUTURE-FEATURES.md)).

## Trampas verificadas

Cosas que se confirmaron contra las fuentes primarias y que conviene no volver a discutir:

- **`versionCode` monótono.** Si una tablet recibe un `versionCode` menor o igual, rechaza la
  actualización. Derivarlo del tag lo hace determinístico; derivarlo de un contador del CI no.
- **Flags de `getPackageArchiveInfo`** (Android 9/10/13): ver etapa 2. Es el error más fácil de cometer
  y solo se manifiesta en la tablet vieja.
- **El proceso se mata al actualizar.** Por defecto todas las instalaciones matan los procesos de la app
  antes de completar; `setDontKillApp` es API 34 y además solo aplica a instalaciones de splits.
- **Rate limit de la API de GitHub**: 60/hora por IP sin token, y el 304 condicional no ahorra cuota sin
  autenticación. Por eso el chequeo va por la URL estable de la release y con intervalos de horas.
- **El `digest` de los assets de la API** existe solo desde junio de 2025 y es `null` en assets
  anteriores: no se depende de él, el hash va en el metadata propio.
- **No hacer certificate pinning** contra `github.com`: un pin vencido dejaría a las tablets sin poder
  actualizarse justamente por el canal que arregla el problema. HTTPS del sistema más la verificación de
  firma end-to-end es más robusto.
- **La firma de debug está commiteada.** Todas las builds de debug comparten clave, así que el sistema
  no distingue una debug de otra: durante el desarrollo, el hash es lo único que identifica al APK
  correcto.
