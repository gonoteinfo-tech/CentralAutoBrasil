# Espelhamento local de tela

O usuário pediu espelhamento de tela em Full HD com áudio e vídeo e instruiu
continuar após conhecer a necessidade de um receptor na central. A RS-918BR
tem Android e instalação de apps segundo a fabricante; sua versão real e
resolução nativa ainda não foram informadas. A implementação adotará receptor
Android 6+ separado, instalado na própria central, fora do Android Auto.

## Comportamento

- O app do telefone ganha acesso a Espelhamento, mantendo rádios e navegador.
- Um APK receptor mostra IP local, código de seis dígitos e a impressão digital
  SHA-256 do certificado. Os dois aparelhos precisam compartilhar uma rede Wi-Fi.
- O telefone conecta ao IP, permite conferir o certificado na central, pede
  as permissões do Android e inicia somente após consentimento explícito.
- Captura MediaProjection do display inteiro; vídeo H.264 em tela de transmissão
  1920x1080 ou 1280x720, 30 fps. A proporção original é preservada com bordas.
  1080p é alvo condicionado a codificador/decodificador compatíveis, não alteração
  da resolução física da central. O usuário pode escolher 720p.
- Áudio interno permitido pelo Android via AudioPlaybackCapture, PCM16 mono
  48 kHz; sem uso do microfone. Áudio pode ser desabilitado. Aplicativos podem
  bloquear a captura; conteúdo protegido não é contornado.
- A central recebe vídeo e áudio. O toque não controla remotamente o telefone.
- Captura tem notificação persistente com Parar. Encerrar pelo Android, bloquear
  o telefone, desconectar ou sair do receptor libera a sessão e seus recursos.
- Uso com veículo estacionado; não se afirma detecção de movimento do veículo.

## Implementação

Módulo Java `mirror-core` contém enquadramento binário limitado e validado,
autenticação do código, metadados do vídeo e impressão digital de certificado.
Android nativo fornece TLS, MediaProjection, MediaCodec, AudioRecord e AudioTrack.
Não há servidor externo, armazenamento de mídia ou bibliotecas de captura novas.

O receptor usa chave persistente no AndroidKeyStore e TLS 1.2+; o transmissor
aceita somente o certificado cuja impressão digital foi conferida pelo usuário.
A inspeção inicial não envia código nem conteúdo. O código vai dentro do TLS.
Somente endereços IPv4 privados são aceitos. Há um transmissor por sessão.

Protocolo v1: HELLO magic 0x52434D31 + seis bytes ASCII do código. WELCOME magic
+ width + height informa a capacidade do receptor. Frames: tipo byte, ptsUs long,
length int, payload. Tipos: FORMAT=1, VIDEO=2, AUDIO=3, STATUS=4, STOP=5.
Máximo de payload 2 MiB; áudio até 16384 bytes e alinhado em PCM16; texto até
1024 bytes; configuração SPS/PPS até 65536 bytes cada. Formato contém width,
height, tamanho+csd0 e tamanho+csd1. Apenas 1920x1080 e 1280x720 são aceitos.

## Verificação e limites

Testes JVM cobrem frames malformados, comprimentos, autenticação, round-trip de
configuração e preservação dos limites. CI compila os dois APKs, executa testes
e lint e verifica assinaturas. Testes instrumentados de codecs serão preparados
quando viáveis. Sem acesso aos aparelhos, o resultado será identificado como
APK de teste e o funcionamento físico, sincronismo e estabilidade de 1080p
continuarão pendentes de validação real. Não se alegará espelhamento testado
apenas porque o build passou.
