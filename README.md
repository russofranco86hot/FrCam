# FrCam Monitor Pro 📹

Aplicación Android universal y completa para visualización, control PTZ y administración avanzada de cámaras de seguridad IP, timbres inteligentes y cámaras en la nube, **100% libre de anuncios**.

Combina y supera las capacidades de apps comerciales como *tinyCam Monitor*, *IP Cam Viewer*, *Onvifer*, y reemplaza la necesidad de aplicaciones propietarias llenas de publicidad invasiva como **V380 Pro**, **Eye4 (VStarcam)**, **v720** y **Ring**.

---

## 🚀 Características Principales

### 🌐 Compatibilidad Universal de Protocolos
- **V380 Pro Nativo**:
  - Conexión tanto por LAN local (puerto 8800) como por **Cloud Relay** mediante ID de dispositivo (`MVT26101` y similares) sin requerir reenvío de puertos ni tocar el router.
  - Reensamblado de fragmentos `0x7F`, descifrado AES-ECB y decodificación por hardware MediaCodec H.264 / H.265.
- **Eye4 / VStarcam**: Soporte TCP/UDP nativo y perfiles de streaming.
- **v720**: Detección y compatibilidad con cámaras P2P de bajo costo.
- **Ring Cloud**:
  - Conexión nativa con autenticación 2FA oficial o Refresh Token.
  - Activación de VOD en vivo, control de estados y notificaciones de movimiento.
- **ONVIF Perfil S/T**: Detección WS-Discovery en red local, captura de perfiles RTSP y control PTZ.
- **RTSP / RTMP / HTTP-MJPEG / HLS**: Reproductor universal con ExoPlayer y SurfaceView de baja latencia con reconexión automática.

### 🎮 Control PTZ y Audio Bidireccional
- Pad de movimiento continuo en 8 direcciones y presets de patrulla.
- **Intercomunicador Bidireccional**: Envío de voz en tiempo real desde el micrófono del smartphone hacia el altavoz de la cámara (G.711 A-law / PCM).
- **Escucha en Vivo**: Audio directo desde la cámara con botón de silenciado rápido en pantalla.

### 🚨 Detección de Movimiento con GIF Animado (Estilo Ring)
- Algoritmo de detección de diferencias entre fotogramas en tiempo real.
- **Notificaciones Push Enriquecidas**: Generación de archivo `.gif` animado con codificación NeuQuant/LZW adjuntado a la notificación del sistema.
- Historial en la app de eventos de movimiento con animación en bucle.

### 💾 Respaldo y Restauración (Exportar / Importar Cámaras)
- Exporta toda la base de datos de cámaras (IDs, contraseñas, configuraciones y ajustes de red) a un archivo `.json`.
- Restaura la configuración con un solo toque al cambiar de dispositivo o reinstalar.

### 🎛️ Pantalla Principal y Modo Cuadrícula Adaptativo
- Modos de visualización: **1x1**, **2x2** y **3x3**.
- **Switch individual de movimiento**: Activa o pausa la transmisión en vivo por cámara en el dashboard para optimizar ancho de banda y rendimiento del procesador gráfico.
- Soporte para **Picture-in-Picture (Ventana Flotante)**.

---

## 🛠️ Tecnologías y Arquitectura

- **Lenguaje**: Kotlin 1.9 / 2.0 (100% Modern Kotlin)
- **UI Toolkit**: Jetpack Compose + Material 3 Design
- **Reproducción de Video**: ExoPlayer (Media3 RTSP / MP4) + MediaCodec NAL Unit Reassembler
- **Persistencia**: Jetpack DataStore Preferences + Kotlinx Serialization
- **CI/CD**: GitHub Actions para compilación automática y generación de Releases con APK adjunto al pushear tags (`v*`).

---

## 📦 Descarga de Releases

Cada nueva versión etiquetada genera automáticamente un instalador `.apk` en la pestaña de **[Releases](https://github.com/russofranco86hot/FrCam/releases)**.

---

## 📄 Licencia

Desarrollado para uso personal y comunitario por Franco Russo.
