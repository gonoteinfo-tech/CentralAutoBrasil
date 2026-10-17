# Validação — Roadstar Connect

Este registro diferencia verificações locais de testes em Android real. A prévia
visual gerada é um conceito, não uma captura de um aplicativo em execução.

## Evidências locais

- RED: compilação de CoreChecks falhou por ausência de UrlPolicy (5 erros de símbolo).
- GREEN: `sh tools/check-core.sh` — 26 verificações de URLs passaram.
- XML: os 36 arquivos de recursos e manifesto existentes até a primeira revisão foram analisados sem erro de sintaxe.
- Ambiente: `java` inclui jdk.compiler; `--release 17` indisponível neste runtime reduzido. A verificação portátil usa `-source 17 -target 17`; isso não é compilação Android.
- Build, lint e testes Gradle: **não executados**. `sh tools/setup-wrapper.sh` falhou com `curl (7): Failed to connect to proxy port 8080`. Gradle e Android SDK não estão disponíveis. Não existe APK compilado nesta entrega.

## Verificação final disponível

- `sh tools/check-core.sh`: 26 verificações reais de URLs passaram.
- Parser Java do JDK: 7 arquivos Java analisados sem erro de sintaxe; não resolve APIs Android/Media3/Material nem substitui compilação.
- XML: 39 arquivos analisados sem erro; referências `R.*` dos fontes às definições locais de recursos foram conferidas.
- `sh -n gradlew tools/setup-wrapper.sh tools/check-core.sh`: sintaxe dos scripts aprovada.
- O empacotamento contém fontes, recursos, instruções e prévia conceitual; não contém APK nem um `gradle-wrapper.jar` fictício.

## Revisão independente e correções

Uma revisão independente examinou os arquivos Java, recursos, manifesto e
configuração contra a especificação e o plano. Nenhum problema crítico foi
identificado. Foram corrigidos no código quatro pontos importantes:

1. Removidas chamadas a métodos inexistentes de inset lateral do MaterialButton;
   corrigidos namespace dos atributos de inset e pais dos estilos de texto.
2. Navegação principal fora dos hosts YouTube também é bloqueada no interceptador
   de requisições, cobrindo POST, com alternativa externa. Subrecursos legítimos
   continuam permitidos.
3. Cabeçalhos da biblioteca passaram para a área rolável; orientação horizontal
   ganhou controles compactos e mais área para o navegador.
4. Alterações do catálogo reconciliam todos os itens enfileirados; exclusões não
   deixam uma fonte antiga acessível por avanço da fila.

Essas correções passaram pelas verificações estáticas disponíveis, mas o build
e os testes em dispositivo continuam pendentes. A verificação XML detectou e
permitiu corrigir um namespace ausente após a reorganização do layout.

Pendências menores conhecidas, mantidas para uma próxima revisão:

- A página restaurada pode ficar desatualizada quando o YouTube muda de vídeo
  pela History API, sem carregar uma página inteira.
- Rotacionar o telefone enquanto outra aba está selecionada pode descartar a
  última localização do navegador e retornar ao início do YouTube.

## Casos no telefone / emulador

Todos os casos abaixo estão **não executados** até existir evidência no dispositivo.

| Caso | Resultado esperado |
|---|---|
| persistencia_apos_reabrir | Rádios e favoritos reaparecem após fechar e abrir. |
| editar_sem_mudar_id | Alteração mantém posição e favorito da fonte. |
| excluir_sem_apagar_outras | Somente a fonte confirmada é removida. |
| catalogo_vazio | Estado vazio útil, sem rádio fictícia ou carregamento infinito. |
| texto_ampliado_200 | Texto e ações continuam acessíveis com fonte a 200%. |
| catalogo_vazio_auto | Android Auto apresenta duas pastas navegáveis, com lista vazia válida. |
| id_removido | Cliente não consegue iniciar uma fonte removida; sem crash. |
| favoritos_atualizados | Alteração no telefone atualiza pasta Favoritos. |
| pausar_retoma_somente_por_toque | Conectar ou sair do navegador não inicia áudio. |
| falha_stream | Falha de conexão/formato tem estado de erro, favoritos preservados. |
| interrupcao_audio | Chamadas e outras fontes respeitam foco de áudio. |
| abrir_youtube | Página oficial abre no telefone e um vídeo público começa após toque. |
| tela_cheia_voltar | Tela cheia entra/sai e o botão Voltar restaura controles. |
| radio_para_navegador | Entrar no navegador pausa a rádio. |
| video_para_segundo_plano | Trocar de aba/app descarrega a página e interrompe mídia oculta. |
| login_externo | Login abre fora da WebView, sem promessa de compartilhar sessão. |
| sem_app_externo | Ausência de app compatível produz mensagem, sem crash. |
| rede_ausente | Falha permite recarregar ou abrir externamente. |
| ssl_invalido | Certificado inválido é rejeitado. |
| rotação_insets | Orientações e barras do Android não cobrem controles. |
| talkback | Ícones de ação têm rótulos e navegação útil. |

Versão da WebView e modelo testado: não disponíveis neste ambiente.
Desktop Head Unit: não executado. Roadstar RS-918BR física: não executado.
