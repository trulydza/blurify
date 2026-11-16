# Blurify for One UI

Panel de volumen estilo MIUI (Blurify) para Samsung One UI, con fondo capturado y difuminado, en lugar del panel de volumen de Samsung.

## Requisitos

- Samsung con One UI (probado en Galaxy A17 5G, One UI 9 / Android 17)
- Root + LSPosed (probado con Vector / JingMatrix LSPosed v2.2)
- Scope `com.android.systemui`
- APK Blurify (`com.miui.volumebar` 1.5) instalado; el APK ya incluye el hook (`assets/xposed_init`)

## Cómo funciona

El hook intercepta `SamsungVolumeDialogImpl` y `VolumePanelImpl.dispatch`, oculta el panel de volumen de Samsung y muestra el overlay de Blurify. Para el fondo, captura una miniatura de la pantalla con `SemWindowManager.screenshot()` y la difumina.

## Instalación

1. Instala `blurify.apk` desde **Releases**.
2. Actívalo en LSPosed/Vector con scope `com.android.systemui`.
3. Reinicia SystemUI:

   ```sh
   adb shell su -c 'kill $(pidof com.android.systemui)'
   ```

4. Ajusta el estilo (normal/liquid) desde la app o el selector de Blurify.

## Estructura

- `src/com/miui/volumebar/xposed/VolumeHook.java` — hook del panel de volumen.
- El APK incluye además la UI de Blurify (`com.miui.volumebar`) y las clases de difuminado.

## Compilar el hook

El APK publicado ya lleva el hook dentro. Para recompilarlo:

1. Compila `VolumeHook.java` contra `android.jar` y la API de Xposed 82.
2. Convierte a DEX con `d8`.
3. Sustituye `classes.dex` y `assets/xposed_init` en el APK.
4. Alinea y firma con `zipalign` + `apksigner`.

## Notas

- En algunas compilaciones de One UI el hook de `VolumePanelWindow.show` no existe; el resto de hooks sigue funcionando como respaldo.
- La captura de fondo usa la API privada `SemWindowManager.screenshot()`, por lo que puede cambiar entre versiones de One UI.
- APK firmado con clave de desarrollo.
