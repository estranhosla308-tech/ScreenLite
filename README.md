# ScreenLite — gravador de tela Android

Projeto Android nativo em Kotlin. Abra a pasta no Android Studio e compile um APK de depuração ou release.

## O que esta versão implementa
- Tela principal escura e lista de vídeos gravados em `Movies/ScreenLite`.
- Controle flutuante com iniciar/parar, atalho para vídeos, configurações e fechar.
- Captura de tela via MediaProjection + MediaRecorder/H.264.
- Opções de resolução, bitrate e FPS.
- Fonte de áudio: sem áudio ou microfone.
- Salva MP4 na coleção pública de vídeos do Android.

## Limitações conhecidas desta primeira versão
- **Áudio interno do sistema e modo Ambos ainda não estão implementados**. A tela de configurações mostra as opções para manter o desenho solicitado, mas o gravador avisa e não inicia nesses dois modos. Captura de áudio interno exige `AudioPlaybackCapture`/`AudioRecord` e uma trilha AAC sincronizada no muxer; além disso, alguns jogos/apps bloqueiam a captura.
- Android 14+ exige consentimento do usuário para cada sessão de captura. Por isso, depois de parar, o controle flutuante fecha; para gravar novamente, volte ao app e toque em “Mostrar controle flutuante” e autorize de novo.
- A opção de resolução reduz a imagem capturada para o lado maior indicado. “Automática” usa a dimensão da tela. O resultado depende das dimensões e densidade do aparelho.
- O FPS e bitrate escolhidos são solicitações ao codificador; o aparelho pode não sustentar os valores mais altos durante jogos pesados.
- O projeto precisa ser compilado e testado em aparelho real. Não foi possível validar o APK nesta sessão com um dispositivo Android conectado.

## Requisitos
- Android 10 (API 29) ou superior.
- Permissão de sobreposição para exibir o controle flutuante.
- Permissão de microfone se escolher Microfone.
- O usuário precisa aprovar a captura de tela na janela oficial do Android.

## Build
Abra no Android Studio com JDK 17 e deixe o Gradle baixar o Android Gradle Plugin. Compile com `:app:assembleDebug`.
