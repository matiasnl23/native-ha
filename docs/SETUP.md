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

## 8. Datos de la app en cada dispositivo

La URL de Home Assistant, el token y el layout del dashboard se guardan **en cada dispositivo**
(el token cifrado con una clave del Android Keystore, que no se puede exportar). Un emulador nuevo en
la Mac arranca en la pantalla de configuración: generá un token nuevo en HA (perfil → Seguridad →
Tokens de acceso de larga duración) o reutilizá uno existente.

Si una actualización agrega un permiso nuevo y la app falla con `socket failed: EPERM` en el
emulador, desinstalá y reinstalá la app (es un problema conocido del emulador, no del código).
