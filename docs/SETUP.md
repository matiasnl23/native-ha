# Setup del entorno (primera vez)

Máquina: Ubuntu 24.04, con snap disponible, ~69GB libres y 62GB RAM — sobra para Android Studio +
SDK (usan entre 8 y 15GB en total).

## 1. Instalar Android Studio

```bash
sudo snap install android-studio --classic
```

Al terminar, lanzarlo:

```bash
android-studio
```

## 2. Setup Wizard inicial (solo la primera vez que abrís Android Studio)

1. "Welcome to Android Studio" → **Next**.
2. Install Type → **Standard** (deja que elija SDK, emulador, etc. por defecto).
3. Elegí un tema (Light/Darcula) → **Next**.
4. Verify Settings: revisa que vaya a descargar el **Android SDK**, **Android SDK Platform**,
   **Android Virtual Device** y sobre todo **Android SDK Platform-Tools** (ahí viene `adb`, lo vas
   a necesitar después) → **Next**.
5. Acepta las licencias (License Agreement) → **Finish**. Va a descargar varios GB, puede tardar
   varios minutos según tu conexión.

## 3. Crear el proyecto

**Importante:** no lo crees directamente dentro de `/home/matias/Proyectos/native-home-assistant`
porque ya tiene archivos (README, `.git`, `.claude/agents/`) y el wizard prefiere una carpeta vacía.
Lo creamos aparte y después lo movemos.

1. En la pantalla de bienvenida: **New Project**.
2. Elegí la plantilla **Empty Activity** (la que muestra el logo de Jetpack Compose — en versiones
   recientes de Android Studio es la única "Empty Activity" y ya usa Compose por defecto; si ves
   una versión "Empty Views Activity" separada, esa es la vieja basada en XML, **no** uses esa).
3. Completá el formulario:
   - **Name:** `HA Kiosk`
   - **Package name:** `com.matiasnl.hakiosk`
   - **Save location:** dejá la que sugiere por defecto (algo como
     `/home/matias/AndroidStudioProjects/HaKiosk`) — **no** la cambies a la carpeta del repo todavía.
   - **Minimum SDK:** `API 26 ("Oreo"; Android 8.0)`
   - **Build configuration language:** **Kotlin DSL (build.gradle.kts)** — importante, no dejes la
     opción Groovy.
   - Si hay un checkbox de **"Create Git repository"**: **dejalo destildado** (ya tenemos git
     inicializado en el repo real, no queremos un segundo repo anidado).
4. **Finish**. Va a tardar unos minutos en el primer "Gradle Sync" (descarga dependencias). Dejalo
   terminar sin tocar nada — vas a ver una barra de progreso abajo.
5. Cuando termine, probá que compile: apretá el botón ▶️ (Run) arriba. Te va a pedir crear un
   dispositivo virtual (emulador) o podés conectar tu celular/tablet por USB con "Depuración USB"
   activada (Ajustes → Opciones de desarrollador → Depuración USB; si no ves "Opciones de
   desarrollador", andá a Ajustes → Acerca del teléfono → tocá 7 veces "Número de compilación").
   Deberías ver una pantalla con "Hello Android!" — eso confirma que el esqueleto compila bien.

## 4. Mover el proyecto generado dentro del repo real

Con Android Studio cerrado (o al menos sin tocar el proyecto), desde una terminal:

```bash
rsync -av --ignore-existing /home/matias/AndroidStudioProjects/HaKiosk/ /home/matias/Proyectos/native-home-assistant/
```

`--ignore-existing` copia todo lo nuevo (carpeta `app/`, `gradle/`, `gradlew`, `build.gradle.kts`,
`settings.gradle.kts`, etc.) pero **no pisa** el `README.md`, `.gitignore` ni `.claude/agents/` que
ya existen en el repo.

Después abrí el proyecto ya movido:

```bash
android-studio /home/matias/Proyectos/native-home-assistant
```

Y dejalo sincronizar de nuevo (puede tardar un poco la primera vez en la nueva ubicación).

## 5. Dejar `adb` disponible en la terminal (lo vas a necesitar más adelante)

Agregá esto a tu `~/.zshrc`:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

Después `source ~/.zshrc` y verificá con:

```bash
adb devices
```

(con el celular/tablet conectado por USB y depuración USB activada, debería listarlo).

## 6. Avisar para continuar

Con el esqueleto compilando y corriendo "Hello Android!" en tu celular o tablet, avisame y seguimos
con el código real del MVP1 (cliente WebSocket de Home Assistant + pantalla del dashboard).
