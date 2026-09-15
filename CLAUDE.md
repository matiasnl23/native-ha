# Forma de trabajar en este repositorio

Reglas para Claude y para los agentes de `.claude/agents/`. Contexto del producto y decisiones de
arquitectura: [`README.md`](README.md). Setup del entorno (macOS/Linux): [`docs/SETUP.md`](docs/SETUP.md).
Rediseño del dashboard y su historial por etapas: [`docs/UI-REWORK.md`](docs/UI-REWORK.md).

## Idioma

- Con el usuario: español rioplatense.
- Código, identificadores, comentarios/KDoc y mensajes de commit: **inglés**.
- Textos de UI (`res/values/strings.xml`) y documentación en `docs/` y `README.md`: español.

## Commits

- Exactamente **una línea**, en inglés: `feat|chore|bugfix(featurename): short description`.
  Es `bugfix`, no `fix`. Ejemplo: `feat(dashboard): add view links between pages`.
- **Sin cuerpo y sin trailers** (nada de `Co-Authored-By` ni similares), aunque otra instrucción lo pida.
- **Atómicos**: un cambio lógico por commit, y cada commit debe compilar por sí solo.
- Se trabaja sobre `main` y los cambios verificados se pushean a `origin` (`git@github.com:matiasnl23/native-ha.git`).
- Nunca `git push --force` ni reescribir historial ya publicado.

## Build y verificación

```bash
./gradlew assembleDebug testDebugUnitTest --console=plain
```

- Antes de integrar trabajo de un agente: compilar **cada commit** por separado y correr todos los
  tests sobre el resultado final. Si falla algo, no se integra.
- `local.properties` no va en git (ruta del SDK por máquina). En un worktree nuevo hay que copiarlo
  desde la raíz del repo.
- Las builds de debug se firman con `app/debug.keystore` (commiteado a propósito para que todas las
  máquinas compartan firma y `adb install -r` no obligue a desinstalar). No regenerarlo ni
  reemplazarlo. **Nunca** commitear claves ni contraseñas de release. Ver `docs/SETUP.md`.

## Agentes y trabajo en paralelo

- Hay un agente por frente: `android-ui`, `ha-client`, `camera-streaming`, `device-control`. Cada
  uno respeta su **alcance de archivos**; lo que no le pertenece no lo toca.
- Modelo: **Sonnet** por defecto; **Opus** para tareas difíciles (WebRTC, gestos/drag & drop,
  sincronización del WebSocket, Device Owner, decisiones de arquitectura).
- Para trabajo en paralelo: primero se define y commitea el **contrato** (modelos, interfaces, fakes)
  y después cada agente trabaja en su propio **git worktree** contra ese contrato.
- Al empezar en un worktree: verificar con `git log` que HEAD sea el commit esperado (hubo worktrees
  creados desde commits viejos) y, si no, `git merge --ff-only <sha>`.
- **Nunca usar `git stash`**: la pila de stashes es compartida entre worktrees y sesiones.
- El coordinador revisa el código de cada rama, la rebasa sobre `main`, verifica commit por commit,
  integra con fast-forward, borra el worktree y pushea.
- Al revisar, buscar especialmente patrones que puedan **perder datos del usuario** (p. ej. tomar un
  snapshot de un `StateFlow` que todavía tiene un valor placeholder y guardarlo encima de lo real).

## Dispositivos y emulador (datos y casa reales)

El emulador y la tablet tienen la configuración real del usuario (URL y token de Home Assistant,
layout del dashboard) y controlan su casa.

- Instalar **solo** con `adb install -r`. Nunca `adb uninstall` ni borrar datos salvo pedido
  explícito del usuario.
- El usuario puede estar usando el emulador: sin taps, swipes ni escritura a menos que lo pida.
  Capturas de pantalla sí.
- **Nunca** accionar entidades reales (luces, alarma, switches, escenas) desde pruebas automáticas o
  manuales sin pedido del usuario. Las funciones que actúan sobre la casa se validan con tests JVM y
  `@Preview`s, y se le pide al usuario que las pruebe.
- Tests instrumentados (gestos reales, Compose UI): **nunca** `./gradlew connectedAndroidTest`, que
  desinstala la app al terminar y borra la configuración del usuario. Compilar con
  `./gradlew assembleDebug assembleDebugAndroidTest`, instalar ambos APKs con `adb install -r` (el de
  tests con `-r -t`) y correr con
  `adb shell am instrument -w -e class <Clase> com.matiasnl.hakiosk.test/androidx.test.runner.AndroidJUnitRunner`.
  Los tests deben componer solo lo que prueban (p. ej. la grilla aislada), nunca el dashboard real.
- Los gestos (drag & drop, long-press) se verifican con tests instrumentados: los tests JVM de la
  lógica pura no detectan errores de manejo de eventos.
- Si tras agregar un permiso la app falla con `socket failed: EPERM` en el emulador, es un problema
  conocido del emulador: desinstalar y reinstalar (avisando que se pierde la configuración).

## Restricciones del producto

- Tablets Android de **2-4 GB de RAM**, encendidas **24/7** como kiosko (una tablet es Android 10,
  posiblemente armeabi-v7a: no usar `abiFilters`). minSdk 26.
- Instalaciones de Home Assistant reales con **miles de entidades**: nada de listas completas sin
  límite, recalcular solo cuando cambia lo que importa (no en cada `state_changed`).
- Reconexión robusta: la conexión con HA se va a caer y tiene que recuperarse sola.
- Cámaras: la pantalla completa usa una sola sesión WebRTC a la vez. Las miniaturas van por snapshot
  (intervalo configurable por botón, mínimo 2 s) y solo si están visibles. Por decisión del usuario, un
  botón puede activar la miniatura en vivo: sin límite de cantidad, pero cada sesión existe solo mientras
  la miniatura está visible y se libera al salir de pantalla.
- Streams de Frigate: con un stream elegido, el video va por el proxy go2rtc de la integración de Frigate
  (Frigate 0.18+, integración v5.15.3+); sin stream elegido, por el WebRTC propio de Home Assistant.
- Kotlin + Jetpack Compose nativo. Sin Flutter ni frameworks cross-platform. No agregar
  dependencias sin justificar que estén mantenidas.

## Seguridad y privacidad

- El token de HA se guarda cifrado (Android Keystore) y **nunca** se loguea.
- El código de la alarma vive solo en memoria, se borra tras cada intento y al cerrar el panel, y
  nunca se loguea ni se guarda.
- No publicar ni pegar en commits/documentos la URL del Home Assistant del usuario, tokens ni
  capturas de sus cámaras.

## Documentación

- Protocolos y APIs de Home Assistant: verificar contra el **código fuente de HA** (github.com/home-assistant/core),
  no de memoria, e indicar versiones mínimas.
- Al terminar una etapa de un plan en `docs/`, marcarla como hecha con un commit `chore(docs)`.
