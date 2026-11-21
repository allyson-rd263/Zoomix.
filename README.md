# Zoomix 1.0

Câmera com melhoria de foto por IA (Gemini ou OpenAI, com a chave de cada usuário), vídeo, Live Photo,
horizon lock e interface de vidro que se adapta ao aparelho.

Base: o ZIP 0.1 (o que já estava testado) + os itens que faltavam.

## Modos
- **Foto:** zoom de 1x a 50x (câmera até o limite do aparelho; acima disso, zoom digital). Foto vai para
  a IA em segundo plano e só a melhorada fica na galeria (Pictures/Zoomix). Botão **Nível** (horizon lock)
  aparece só em celulares fortes: gira e recorta a foto para o horizonte ficar reto (até 30° de inclinação).
- **Live:** foto + clipe de ~2,5 s a partir do clique, salvos como Motion Photo (JPEG com vídeo embutido)
  em Pictures/Zoomix. Não passa pela IA. Zoom só óptico (máx. 10x). A resolução da foto Live pode ser
  menor que a do modo Foto, porque o aparelho usa três câmeras lógicas ao mesmo tempo.
- **Vídeo:** gravação com a maior qualidade disponível (até 4K, configurável em Ajustes), estabilização
  quando o aparelho suporta, microfone opcional. Salva em Movies/Zoomix. Zoom óptico, máx. 10x.

## Ajustes
Provedor de IA e chave (criptografada no aparelho), testar chave, instruções e avisos de cada provedor,
**última melhoria** (diagnóstico do envio à IA), manter a original, qualidade de vídeo, microfone e
nível da interface (Automático/Leve/Completo).

## Não incluído nesta versão
- Horizon lock no vídeo (exige processar cada quadro em OpenGL).
- Desfoque e refração reais no vidro líquido (os painéis são translúcidos, em versão leve ou com brilho).
- Gravação de vídeo em 60 fps e HDR de vídeo.
- Live Photo com momento ANTES do clique.

## Como compilar
1. Abra a pasta no Android Studio (ou AndroidIDE) e deixe o Gradle sincronizar (Gradle 8.2 / AGP 8.2.2).
   Ou suba o ZIP para o GitHub e use o fluxo "Build APK".
2. Android 8.0 (API 26) ou mais novo.

## Atenção
- Não foi compilado nem testado em aparelho. Se houver erro de compilação, copie o texto do erro.
- Conhecido: a foto melhorada pela IA pode não chegar na galeria (a investigar). Em Ajustes, "Última
  melhoria" mostra o motivo de cada tentativa.
- Nomes de modelos de IA ficam em AiClients.kt e podem mudar nos provedores.
