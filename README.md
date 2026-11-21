# Zoomix 1.1

Câmera com melhoria de foto por IA (Gemini ou OpenAI, com a chave de cada usuário), HDR próprio,
vídeo, Live Photo, horizon lock, câmera frontal, troca de lentes e interface de bolhas (estilo iPhone).

## O que mudou da 1.0 para a 1.1
- **Vídeo:** agora grava em arquivo temporário e copia para Movies/Zoomix (corrige o erro 5). Se der erro,
  a tela mostra o código e o motivo. A prévia do modo Vídeo é 16:9, inteira (sem esticar).
- **Câmera frontal** (botão Virar). Selfies vão direto para a galeria, sem IA (dá para ligar em Ajustes).
- **Interface:** bolhas flutuantes só com contorno; zoom por bolhas (1x, 2x, 5x...) e pinça; modos em bolhas.
- **Botão da galeria** com miniatura (Android 10+): abre a última foto/vídeo no app padrão do Android.
- **Mini-tela do zoom digital** no canto superior esquerdo (celular forte; configurável em Ajustes).
- **HDR do Zoomix** (modo Foto, câmera traseira): bolha HDR alterna desl. / auto / natural / forte.
  3 fotos (-2 EV, normal, +2 EV) alinhadas e fundidas; as cores vêm da foto normal. "Natural" mistura 70%
  com a normal; "Forte" usa o HDR completo. Demora alguns segundos e não tem prévia ao vivo.
- **Lentes:** bolhas Ultra / Principal / Macro / Tele aparecem SÓ para as lentes que o aparelho entrega a
  apps de terceiros (muitos celulares não entregam). Se o aparelho aceitar zoom abaixo de 1x, aparece
  uma bolha de zoom abaixo de 1x. Em Ajustes há a lista das câmeras detectadas.

## Não incluído
- Deslizar para trocar de modo (use as bolhas).
- Horizon lock no vídeo; desfoque/refração reais no vidro; vídeo 60 fps/HDR; Live com momento antes do clique.
- Tocar para focar e controle manual de exposição.
- HDR do fabricante (CameraX Extensions).

## Como compilar
Android Studio / AndroidIDE (Gradle 8.2, AGP 8.2.2), ou suba o ZIP no GitHub e use o fluxo "Build APK".
Android 8.0 (API 26) ou mais novo.

## Atenção
- Não foi compilado nem testado em aparelho. Se houver erro, copie o texto do erro.
- Conhecido: a foto melhorada pela IA pode não chegar na galeria (a investigar). Em Ajustes, "Última
  melhoria" mostra o motivo de cada tentativa.
- O HDR usa muita memória; se falhar, o app salva a foto normal e avisa.
