# Validação — Roadstar Connect

Este registro diferencia verificações locais de testes em Android real. A prévia
visual gerada é um conceito, não uma captura de um aplicativo em execução.

## Evidências locais

- RED: compilação de CoreChecks falhou por ausência de UrlPolicy (5 erros de símbolo).
- GREEN: `sh tools/check-core.sh` — 26 verificações de URLs passaram.
- XML: os 36 arquivos de recursos e manifesto existentes até a primeira revisão foram analisados sem erro de sintaxe.
- Ambiente: `java` inclui jdk.compiler; `--release 17` indisponível neste runtime reduzido. A verificação portátil usa `-source 17 -target 17`; isso não é compilação Android.
- O build local foi bloqueado: `sh tools/setup-wrapper.sh` falhou com `curl (7): Failed to connect to proxy port 8080`. Gradle e Android SDK não estavam disponíveis neste ambiente. A compilação foi concluída posteriormente no GitHub Actions, conforme as evidências abaixo.

## Verificação final disponível

- `sh tools/check-core.sh`: 26 verificações reais de URLs passaram.
- Parser Java do JDK: 7 arquivos Java analisados sem erro de sintaxe; não resolve APIs Android/Media3/Material nem substitui compilação.
- XML: 39 arquivos analisados sem erro; referências `R.*` dos fontes às definições locais de recursos foram conferidas.
- `sh -n gradlew tools/setup-wrapper.sh tools/check-core.sh`: sintaxe dos scripts aprovada.
- O pacote de fontes contém recursos, instruções e prévia conceitual; o APK é distribuído separadamente pelo GitHub Actions. O projeto não contém um `gradle-wrapper.jar` fictício.

## Espelhamento e dois APKs 0.2.0 — 18/10/2026

- Código compilado: `f04795053d9dd9812bf3e1028dc158e71ebb70e8`, branch `codex/roadstar-connect-apk`.
- [Execução 39131858649](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39131858649): build concluído, **BUILD SUCCESSFUL in 2m 22s**. Passaram `:mirror-core:check`, testes unitários do telefone, lint e montagem dos dois aplicativos e dos APKs instrumentais.
- `sh tools/check-core.sh`: **26 verificações de URLs + 6 de migração do catálogo** passaram. `sh tools/check-mirror-core.sh`: **1724 verificações do protocolo e validação** passaram, incluindo frames concorrentes, limites, truncamento, autenticação e formato.
- Sintaxe Python do destino de testes e **43 XMLs** Android verificados; workflow YAML analisado localmente. Essas verificações não substituem o build Android acima.
- Assinaturas: os dois comandos `apksigner verify --verbose` retornaram **Verifies**, com esquema v2 válido.
- [ZIP Roadstar-Connect-APK](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39131858649/artifacts/12137718543): 8.167.655 bytes, contém `Roadstar-Connect-0.2.0-debug.apk`, `Roadstar-Receiver-0.2.0-debug.apk` e `SHA256SUMS.txt`. Disponível por 30 dias após a execução.
- Revisão independente: corrigida a ordem FORMAT antes do áudio; três códigos incorretos completos acionam espera de 30 segundos; o código muda ao iniciar outra sessão. Consultas de certificado não consomem tentativas. Nenhum bloqueador restante identificado na revisão focada.
- O primeiro teste do receptor identificou `Incompatible padding mode` no AndroidKeyStore. A chave TLS agora autoriza a operação RSA usada pelo Conscrypt, com alias novo; timeout configurado antes do handshake.
- Emulador: Android 11/API 30, imagem Google APIs x86_64, perfil Pixel 2 e renderização SwiftShader no GitHub Actions. **Dois testes do receptor + um teste do telefone passaram**; os resultados são separados, não um teste de dois aparelhos físicos.
- Receptor: TLS com certificado do AndroidKeyStore, pareamento, decodificação AVC 1280×720, pixel vermelho observado na SurfaceView, AudioTrack ativo e encerramento da conexão ao fechar a Activity. O teste negativo rejeitou uma impressão digital diferente antes de enviar o código. A atividade do AudioTrack não comprova volume ou sincronismo percebido em alto-falantes reais.
- Telefone: fluxo real de conferência do certificado, consentimento MediaProjection, serviço de primeiro plano e AudioPlaybackCapture. O destino TLS local recebeu FORMAT 1920×1080 antes da mídia, **26 frames de vídeo, 145 de áudio e 210.848 amostras PCM não nulas**. A ação Parar encerrou a sessão e a conexão. O teste não grava tela nem amostras de áudio.
- O teste do receptor revelou e permitiu corrigir fechamento TLS na thread principal. O cleanup agora usa referências da sessão antiga em uma thread própria; o reinício aguarda brevemente apenas conflitos de porta. A revisão focada confirmou as correções.
- O gerador sintético foi corrigido para usar o horário real dos quadros, como a captura do telefone. Forçar timestamps ideais de 30 fps sob carga de renderização de software fabricava atraso. Na execução final, produziu 72 quadros em 5,16 s. Os limites de atraso de 3 segundos, filas e critérios de imagem/áudio foram preservados. **O teste não certifica 30 fps sustentados**, nem no emulador nem na central.
- Logs: artefato [Roadstar-receptor-emulador](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39131858649/artifacts/12138342899); contém os dois resultados instrumentais e o relatório do destino TLS de teste.
- Não houve acesso ao telefone Android 17 nem à Roadstar física. Desempenho sustentado, 1080p no hardware real, áudio de cada aplicativo, sincronismo percebido, rotação e bloqueio/reconexão precisam de validação nesses aparelhos. Conteúdo protegido pode impedir imagem ou áudio; não há controle remoto por toque.
- [Instalação e conexão](espelhamento.md): dois APKs, mesma rede Wi-Fi, fora do Android Auto. A resolução física da central não é aumentada pelo encoder 1080p.

## Catálogo e APK 0.1.1 — verificação final

- Código compilado: `8ef90d8fb95b15f32e95fbccded6c8714ac27a6a`.
- Catálogo: 20 emissoras, sendo 3 de Anápolis, 5 de outras cidades de Goiás e 12 de outras regiões do Brasil. [Lista e fontes](radios.md).
- Checagem real das transmissões: [execução 39072774934](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39072774934), concluída com sucesso. **20/20** amostras apresentaram áudio AAC/MP3 por HTTPS, incluindo streams HLS. Não foram guardadas amostras de áudio.
- Verificações portáteis: **26 de URLs + 6 de mesclagem do catálogo** passaram. O teste primeiro falhou por ausência de StationCatalog; depois passou com a implementação.
- A mesclagem verifica preservação de nomes/IDs/favoritos, ordem, deduplicação por ID ou URL, lista original intacta, instalação nova e idempotência. Persistência Android e execução de listeners continuam sem teste em dispositivo.
- Java: 8 arquivos analisados sem erro de sintaxe; XML, JSON e sintaxe Python conferidos.
- Build: [execução 39072775004](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39072775004), **BUILD SUCCESSFUL in 2m 5s**. Passaram CoreChecks, `testDebugUnitTest`, `lintDebug` e `assembleDebug`.
- Assinatura: `apksigner verify --verbose` retornou **Verifies**, com APK Signature Scheme v2 válido.
- Download: [Roadstar-Connect-APK](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39072775004/artifacts/12115479407), ZIP de 8.102.623 bytes com `Roadstar-Connect-0.1.1-debug.apk` e `SHA256SUMS.txt`.
- A revisão independente não encontrou bloqueios concretos na migração. O catálogo e sua versão são gravados juntos; dados salvos inválidos não são substituídos; exclusões e edições posteriores são mantidas.
- Compatibilidade com Android 17, reprodução no telefone, persistência real e integração com a Roadstar continuam pendentes de teste físico. Checar amostras no servidor não equivale a testar o player Android.
- APKs de depuração gerados em execuções separadas podem ter assinaturas diferentes. Conflito de assinatura pode exigir desinstalação, que apaga os dados locais; a preservação da migração pressupõe uma atualização aceita pelo Android.

## Compilação anterior — versão 0.1.0

- Data: 17/10/2026, execução [39071846421](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39071846421), concluída com sucesso.
- Commit do código compilado: `5f8f4237793d31a175adeb109010eadd1f507e31`, branch `codex/roadstar-connect-apk`.
- Ambiente: Ubuntu 24.04, Temurin Java 17, Gradle 8.13, Android SDK 36 e Build-Tools 36.0.0.
- Comando: `gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace`.
- Resultado: **BUILD SUCCESSFUL in 2m 19s**. Compilação Java, testes unitários e lint concluídos; nenhum erro bloqueante de lint.
- Assinatura: `apksigner verify --verbose` retornou **Verifies**, com APK Signature Scheme v2 válido.
- Artefato [Roadstar-Connect-APK](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39071846421/artifacts/12115413223): ZIP de 8.100.155 bytes, contendo `Roadstar-Connect-0.1.0-debug.apk` e `SHA256SUMS.txt`.
- Os relatórios de lint e testes estão no artefato `Roadstar-Connect-relatorios` da mesma execução.

Na preparação da CI, foi removido o pedido de instalação do pacote SDK obsoleto
`tools`. O lint também identificou a declaração ausente de `MEDIA_PLAY_FROM_SEARCH`.
A correção registra e trata esse intent na Activity e resolve buscas recebidas
pela sessão Media3 contra o catálogo de rádios. O pedido não aceita uma URL de
reprodução fornecida externamente. Pesquisa sem nome na Activity pede uma seleção.

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

Essas correções passaram pelas verificações estáticas e pelo build da CI.
Os testes em dispositivo continuam pendentes. A verificação XML detectou e
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
| catalogo_inicial | Primeira abertura da versão 0.1.1 adiciona 20 rádios prontas; favoritos começam vazios. |
| migracao_catalogo | Cadastro anterior, nomes editados e favoritos são preservados; IDs e URLs existentes não são duplicados. |
| excluir_radio_incluida | Rádio do catálogo excluída não reaparece ao reabrir o app. |
| editar_sem_mudar_id | Alteração mantém posição e favorito da fonte. |
| excluir_sem_apagar_outras | Somente a fonte confirmada é removida. |
| catalogo_vazio | Estado vazio útil, sem rádio fictícia ou carregamento infinito. |
| texto_ampliado_200 | Texto e ações continuam acessíveis com fonte a 200%. |
| catalogo_vazio_auto | Android Auto apresenta duas pastas navegáveis, com lista vazia válida. |
| id_removido | Cliente não consegue iniciar uma fonte removida; sem crash. |
| favoritos_atualizados | Alteração no telefone atualiza pasta Favoritos. |
| pesquisa_por_voz | Nome de uma rádio cadastrada é resolvido pela sessão; buscas sem resultado falham sem carregar uma URL externa. |
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
