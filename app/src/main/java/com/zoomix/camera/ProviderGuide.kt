package com.zoomix.camera

class Guide(val steps: List<String>, val warnings: List<String>)

/** Textos que aparecem só na tela de configurar a chave. Os sites mudam: revise de vez em quando. */
object ProviderGuide {
    fun of(p: Provider): Guide = when (p) {
        Provider.GEMINI -> Guide(
            steps = listOf(
                "Acesse aistudio.google.com e entre com sua conta Google.",
                "Toque em \"Get API key\" e crie uma chave.",
                "Cole a chave aqui, toque em Salvar e depois em Testar chave."
            ),
            warnings = listOf(
                "A chave é grátis, mas os modelos que editam imagem normalmente são pagos: pode ser preciso ativar a cobrança no Google AI Studio.",
                "Sua foto é enviada ao Google para ser melhorada.",
                "O custo ou a cota é da sua conta.",
                "A IA reinterpreta a imagem: pode mudar rostos e detalhes.",
                "Não compartilhe sua chave com ninguém."
            )
        )
        Provider.OPENAI -> Guide(
            steps = listOf(
                "Acesse platform.openai.com e entre na sua conta.",
                "Abra a seção \"API keys\" e crie uma chave.",
                "Cole a chave aqui, toque em Salvar e depois em Testar chave."
            ),
            warnings = listOf(
                "Exige crédito pago na conta, e o modelo de imagem pode pedir verificação da organização.",
                "Sua foto é enviada à OpenAI para ser melhorada.",
                "O custo é da sua conta.",
                "A IA reinterpreta a imagem: pode mudar rostos e detalhes.",
                "Não compartilhe sua chave com ninguém."
            )
        )
    }
}
