# Zoomix 0.1 (etapa 1)

Câmera simples + melhoria de foto por IA (Gemini ou OpenAI, com a chave de cada usuário).

## O que já tem
- Modo Foto: zoom de 1x a 50x (câmera até o limite do aparelho; acima disso, zoom digital).
- Envio em segundo plano (WorkManager) para a IA escolhida; só a versão melhorada vai para a galeria (Pictures/Zoomix).
- A original só é apagada depois que a melhorada foi salva. Se a IA falhar de vez ou não houver chave, a original vai para a galeria.
- Ajustes: provedor (Gemini/OpenAI), chave criptografada no aparelho, botão "Testar chave", instruções e avisos de cada provedor, opção de manter a original, nível da interface (Automático/Leve/Completo).

## O que ainda NÃO tem (próximas etapas)
- Vídeo, Live Photo (Motion Photo) e horizon lock.
- Vidro líquido com desfoque/refração reais (hoje: painéis translúcidos, leve ou com brilho).
- Ícone do app.

## Como compilar
1. Abra a pasta no Android Studio (ou AndroidIDE no celular) e deixe sincronizar o Gradle.
   O arquivo gradle-wrapper.jar não vem no ZIP: o Android Studio/AndroidIDE usam o Gradle deles
   (versão 8.2 ou compatível com AGP 8.2.2).
2. Rode em um aparelho Android 8.0 (API 26) ou mais novo.

## Atenção
- O código não foi compilado nem testado em aparelho. Podem aparecer erros de compilação; mande a mensagem de erro para ajustar.
- Os nomes dos modelos estão em AiClients.kt (GEMINI_MODEL, OPENAI_MODEL) e podem mudar nos provedores.
- A IA pode devolver a imagem em resolução MENOR que a da foto original. Teste com "Manter a foto original" ligado antes de confiar no descarte da original.
