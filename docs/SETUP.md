# Setup del entorno de desarrollo (macOS o Linux)

El proyecto ya existe en GitHub: en una máquina nueva solo hay que instalar Android Studio, clonar y
abrir. Nada de la configuración de build depende del sistema operativo:

- `gradlew` es ejecutable y usa Gradle 9.x; la JVM del daemon (Java 25) se descarga sola la primera
  vez para macOS (Intel y Apple Silicon), Linux y Windows (`gradle/gradle-daemon-jvm.properties`).
- La ruta del SDK vive en `local.properties`, que **no** está en git: Android Studio lo crea al abrir
  el proyecto en cada máquina.

Espacio necesario: ~15 GB entre Android Studio, SDK y un emulador.

## 1. Instalar Android Studio

**macOS**

- Descargá el `.dmg` desde [developer.android.com/studio](https://developer.android.com/studio)
  eligiendo **Mac with Apple chip** (M1/M2/M3/M4) o **Mac with Intel chip** según tu Mac
  ( → Acerca de esta Mac). Arrastrá Android Studio a Aplicaciones.
- Alternativa con Homebrew: `brew install --cask android-studio`.

**Linux (Ubuntu)**

```bash
sudo snap install android-studio --classic
```

## 2. Setup Wizard (primera vez que abrís Android Studio)

1. Install Type → **Standard**.
2. Verificá que vaya a descargar **Android SDK**, **Android SDK Platform**, **Android Virtual Device**
   y **Android SDK Platform-Tools** (trae `adb`).
3. Aceptá las licencias → **Finish** (descarga varios GB).

El SDK queda en:

| Sistema | Ruta del SDK |
|---|---|
| macOS | `~/Library/Android/sdk` |
| Linux | `~/Android/Sdk` |

## 3. Git y acceso a GitHub por SSH

El repo se clona por SSH (`git@github.com:matiasnl23/native-ha.git`).

**macOS:** `git` viene con las Command Line Tools; si no las tenés, correr `git --version` ofrece
instalarlas (o `xcode-select --install`).

Si la máquina todavía no tiene una clave SSH registrada en GitHub:

```bash
ssh-keygen -t ed25519 -C "tu-email"
# macOS: guardar la passphrase en el llavero
ssh-add --apple-use-keychain ~/.ssh/id_ed25519   # en Linux: ssh-add ~/.ssh/id_ed25519
cat ~/.ssh/id_ed25519.pub   # pegar en GitHub → Settings → SSH and GPG keys → New SSH key
ssh -T git@github.com       # debería saludarte con tu usuario
```

Configurá también tu identidad de git en esa máquina (`git config --global user.name` /
`user.email`).

## 4. Clonar y abrir

```bash
git clone git@github.com:matiasnl23/native-ha.git
```

En Android Studio: **Open** → la carpeta clonada. Dejá terminar el primer **Gradle Sync** (descarga
dependencias y la JVM del daemon). Android Studio crea `local.properties` con `sdk.dir` apuntando a
la ruta de la tabla anterior.

## 5. Dispositivo para correr la app

**Emulador:** Device Manager → **Create Virtual Device** → una tablet (p. ej. *Pixel Tablet*) →
imagen de sistema reciente.

- En Macs con Apple Silicon usá imágenes **arm64-v8a** (las x86/x86_64 no corren ahí). La app
  incluye libwebrtc para todas las arquitecturas, así que las cámaras funcionan igual.
- En Linux/Intel, las imágenes **x86_64** son las más rápidas.

**Tablet o celular real por USB:** Ajustes → Acerca del dispositivo → tocar 7 veces "Número de
compilación" → Opciones de desarrollador → **Depuración USB**. En macOS no hace falta instalar
drivers.

Luego ▶️ (Run) en Android Studio.

## 6. `adb` y `./gradlew` desde la terminal

Agregá al perfil de tu shell (`~/.zshrc` en macOS y en este Linux; `~/.bashrc` si usás bash):

```bash
# macOS
export ANDROID_HOME="$HOME/Library/Android/sdk"
# Linux
# export ANDROID_HOME="$HOME/Android/Sdk"

export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

`./gradlew` necesita un JDK 17 o superior para arrancar. Si no tenés uno instalado, usá el que trae
Android Studio:

```bash
# macOS
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# Linux (snap)
# export JAVA_HOME="/snap/android-studio/current/jbr"
```

Abrí una terminal nueva (o `source ~/.zshrc`) y verificá:

```bash
adb devices
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb install -r` actualiza la app **sin borrar sus datos** (URL y token de Home Assistant, layout del
dashboard). Evitá `adb uninstall` salvo que quieras empezar de cero.

## 7. Firma de debug compartida

Las builds de debug se firman con `app/debug.keystore`, que está en el repo a propósito (contraseña y
alias estándar de Android: `android` / `androiddebugkey`). Así todas las máquinas (esta Linux, la
MacBook, los agentes) generan APKs con **la misma firma**, y `adb install -r` puede actualizar una
app instalada desde otra computadora **sin borrar sus datos**.

Sin esto, cada máquina firma con su propio `~/.android/debug.keystore` y la instalación falla con
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`; la única salida sería desinstalar y perder la configuración.

- No reemplaces ni regeneres ese archivo: la tablet, el celular y el emulador ya tienen la app firmada
  con esa clave.
- Es **solo para debug**. La firma de release (tablets de kiosko en producción) usará otra clave que
  **nunca** se sube al repo. Antes de provisionar Device Owner en una tablet de kiosko, conviene
  instalarle la build de release y desactivar la depuración USB (cambiar de firma implica reinstalar
  una vez).

## 8. Firma de release y secrets de GitHub Actions

Las builds de release —las que se instalan en las tablets de kiosko y después se actualizan solas— se
firman con una clave **propia que nunca se sube al repo**. Android solo acepta una actualización si
está firmada con la misma clave que la versión ya instalada, así que **si esa clave se pierde, la
única salida es desinstalar la app en cada tablet y perder su configuración**. Es lo más delicado del
proyecto: tratala mejor que a cualquier contraseña.

Esto es el prerrequisito de la etapa 1 de [`RELEASE-OTA.md`](RELEASE-OTA.md), que agrega el
`signingConfig` de release y el workflow que consume estos secrets.

### Generar la keystore (una sola vez en la vida del proyecto)

`keytool` viene con el JDK; si no lo tenés en el `PATH`, usá el de Android Studio (el mismo
`JAVA_HOME` de la sección 6):

```bash
"$JAVA_HOME/bin/keytool" -genkeypair -v \
  -keystore ~/hakiosk-release.keystore \
  -storetype PKCS12 \
  -alias hakiosk \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Pide una contraseña y datos de identidad (nombre, organización, país). No los valida nadie, pero
quedan dentro del certificado que ve Android. Con PKCS12 la contraseña del store y la de la clave son
la misma.

- Guardala **fuera del repo** (el ejemplo la deja en `~/`). `.gitignore` ya ignora `*.keystore` y
  `*.jks` con la excepción de `debug.keystore`, pero no dependas de eso: que el archivo nunca esté
  adentro del árbol de trabajo.
- **Backup en al menos dos lugares que no sean esta máquina** (gestor de contraseñas, disco externo
  cifrado). Guardá los tres datos juntos: archivo, contraseña y alias. Sin los tres no sirve de nada.
- `-validity 10000` son unos 27 años: la clave tiene que sobrevivir a todas las versiones futuras.

### Cargar los secrets en GitHub

En el repo, dentro del environment **`PRODUCTION`**: **Settings → Environments → `PRODUCTION` → Add
environment secret**. Son cuatro:

| Secret | Contenido |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | La keystore codificada en base64, **en una sola línea** |
| `RELEASE_KEYSTORE_PASSWORD` | La contraseña del store |
| `RELEASE_KEY_ALIAS` | `hakiosk` (el `-alias` de arriba) |
| `RELEASE_KEY_PASSWORD` | La contraseña de la clave (con PKCS12, la misma del store) |

```bash
base64 -w0 ~/hakiosk-release.keystore    # en macOS: base64 -i ~/hakiosk-release.keystore
```

El `-w0` importa: sin él `base64` corta la salida en líneas de 76 caracteres, y el enmascarado de
secrets en los logs de Actions funciona por coincidencia exacta — un secret multilínea puede terminar
apareciendo en claro. Copiá la línea entera (son unos 4 KB; el límite de un secret es 48 KB) y pegala
como valor.

Dos cosas que ya están de nuestro lado: los secrets **no se exponen a workflows disparados desde
forks**, y el workflow de release corre solo por tag `v*` o a mano, así que nadie externo puede
provocar que se descifre la keystore.

### Qué implica que vivan en un environment y no en el repo

Guardarlos en un environment agrega tres reglas que hay que tener presentes (documentación de GitHub,
*Deployments and environments*):

1. **El job tiene que declarar el environment.** "Secrets stored in an environment are only available
   to workflow jobs that reference the environment": el job de release lleva
   `environment: PRODUCTION`. Sin esa línea el workflow igual corre, pero los cuatro secrets llegan
   **vacíos** y la firma falla con un error que no menciona los secrets — es el error más difícil de
   diagnosticar de todo este setup.
2. **Si el environment pide revisores, el job espera.** Con *required reviewers* configurados, el job
   queda en estado *Waiting* y "a job cannot access environment secrets until one of the required
   reviewers approves it". Para un proyecto de una sola persona conviene dejarlo sin revisores; si no,
   cada tag va a quedar esperando una aprobación manual.
3. **Si restringís los refs, la regla del tag va aparte.** En *Deployment branches and tags*, la opción
   "Selected branches and tags" se evalúa contra el `GITHUB_REF` de la corrida, y los patrones "must be
   configured for branches or tags individually": una regla de **branch** no habilita un **tag**. Como
   el release se dispara pusheando `v1.4.2`, hace falta una regla de tipo **Tag** con patrón `v*`, o el
   job no arranca. Los comodines no cruzan `/`. Si dejaste el environment sin restricciones, no hay
   nada que configurar.

### Verificar que quedó bien

Con el primer APK publicado:

```bash
"$ANDROID_HOME"/build-tools/<versión>/apksigner verify --print-certs app-release.apk
```

El SHA-256 del certificado tiene que ser **el mismo en todas las versiones**: es la huella que la app
compara antes de instalar una actualización. Anotalo; el certificado es público, no hay problema en
compartirlo.

## 9. Datos de la app en cada dispositivo

La URL de Home Assistant, el token y el layout del dashboard se guardan **en cada dispositivo**
(el token cifrado con una clave del Android Keystore, que no se puede exportar). Un emulador nuevo en
la Mac arranca en la pantalla de configuración: generá un token nuevo en HA (perfil → Seguridad →
Tokens de acceso de larga duración) o reutilizá uno existente.

Si una actualización agrega un permiso nuevo y la app falla con `socket failed: EPERM` en el
emulador, desinstalá y reinstalá la app (es un problema conocido del emulador, no del código).
