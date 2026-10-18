# Roadstar Receptor 0.2.0

APK Android 6+ para instalar na própria central. Abra esta tela e mantenha-a
visível. Na mesma rede Wi-Fi, o telefone usa o IP local, o código de seis dígitos
e a impressão SHA-256 exibidos aqui. O certificado RSA é persistente no
AndroidKeyStore; o código é novo a cada criação da Activity.

O receptor negocia H.264 1920×1080/30 fps quando um decodificador local anuncia
suporte, senão 1280×720/30 fps. A resolução física da central não é alterada.
O SurfaceView preserva 16:9; a imagem do telefone recebe bordas no transmissor.
Áudio PCM16 mono de 48 kHz usa AudioTrack com foco de áudio. Nenhum toque é
enviado de volta ao telefone.

A porta local é 8999. Somente IPv4 RFC 1918 e um transmissor por vez são aceitos.
TLS 1.2 protege código e mídia; o telefone deve conferir o SHA-256 antes de
conectar. O servidor não transmite mídia, envia apenas a negociação inicial.
Consultas que leem o certificado e fecham o TLS não iniciam reprodução.

Vídeo usa fila de até 16 unidades de acesso (cada frame até 2 MiB), áudio até
32 blocos (até 16384 bytes cada). Se a central ficar para trás, a conexão é
encerrada em vez de acumular memória. Áudio e vídeo compartilham relógio
monotônico, com buffer inicial de 150 ms; a sincronização e a capacidade real
de 1080p precisam ser confirmadas em cada aparelho.

Sair da Activity, perder o foco, parar o receptor ou desconectar fecha a sessão.
A perda de foco de áudio também interrompe a transmissão. Encerrado um cliente,
o servidor volta à espera; o botão Parar fecha a porta. Não há captura de
microfone, desvio de DRM, servidor externo ou controle remoto do telefone.

A compilação/lint e os testes de protocolo não substituem testes físicos na
Roadstar. Use com o veículo estacionado. A versão real do Android da central e
sua capacidade de instalar este APK ainda precisam ser verificadas no aparelho.
