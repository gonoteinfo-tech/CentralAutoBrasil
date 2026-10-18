# Espelhamento — versão de teste 0.2.0

O Roadstar Connect envia a tela do telefone e o áudio interno permitido pelo
Android para o **Roadstar Receptor**, instalado diretamente na central.
A conexão é local por Wi-Fi, fora do Android Auto. Não utiliza servidor externo
e não guarda gravações. Use com o veículo estacionado.

## Instalar e conectar

1. Instale `Roadstar-Connect-0.2.0-debug.apk` no **telefone**.
2. Instale `Roadstar-Receiver-0.2.0-debug.apk` na **central Android**. O receptor
   exige Android 6 ou posterior. A versão real da sua Roadstar ainda precisa
   ser conferida; não é possível instalar esse APK dentro do Android Auto.
3. Conecte os dois aparelhos à mesma rede Wi-Fi. Você pode compartilhar a
   internet do telefone e conectar a central à rede criada. Desconecte o Android
   Auto sem fio antes de usar essa rede, se ele estiver ocupando o Wi-Fi.
4. Abra **Roadstar Receptor** na central e deixe a tela aberta. Ela mostra um
   IP local, código de seis dígitos e impressão digital do certificado.
5. No telefone, abra **Início → Espelhar tela**. Informe o IP e o código
   exibidos na central. Escolha 1080p ou 720p e se deseja áudio interno.
6. Conecte e confira se a impressão digital apresentada no telefone é igual à
   exibida na central. Confirme somente se coincidir. A inspeção inicial não
   envia código nem imagem; a transmissão seguinte verifica esse certificado.
7. Autorize a captura de áudio, se escolhida, e a captura de tela solicitada
   pelo Android. Quando aparecer a escolha, selecione **Tela inteira**.
8. Abra o aplicativo que deseja mostrar e controle-o pelo telefone. Gire o
   telefone para a horizontal para aproveitar melhor a tela da central.

Ao receber o vídeo, o receptor entra em tela cheia. O botão **Conexão** mostra
novamente os controles. Use **Parar** no telefone/notificação ou encerre o
receptor. Bloquear o telefone também encerra a captura nas versões recentes
do Android. Toda reconexão solicita uma nova autorização de captura.

## Imagem e áudio

- 1080p transmite em uma superfície de 1920×1080; 720p usa 1280×720, com alvo
  de 30 quadros por segundo. A proporção é preservada, com bordas se necessário.
- A disponibilidade de 1080p depende dos codecs e do desempenho dos dois
  aparelhos. Se não for suportado, selecione 720p. A transmissão não aumenta
  a resolução física da tela da Roadstar.
- O áudio transmitido é PCM mono de 48 kHz. Ele vem da reprodução permitida
  pelos aplicativos, não do microfone. Chamadas e conteúdo bloqueado não são
  garantidos. Alguns vídeos protegidos podem aparecer sem imagem ou som.
- Wi-Fi instável pode causar atraso ou encerramento. A sincronização de áudio
  e vídeo e o desempenho prolongado precisam de teste na central real.

## Se não conectar

- Confirme a rede, o IP atual e o código. O IP é apenas o número, como
  `192.168.43.20`; a porta usada pelo receptor é 8999.
- Redes de convidados podem impedir comunicação entre aparelhos. Experimente
  a rede compartilhada pelo telefone ou uma rede Wi-Fi local sem isolamento.
- Mantenha o receptor aberto. Sair da tela encerra a recepção e libera áudio,
  vídeo e conexão. Ao voltar, verifique novamente o IP e o código.
- Prefira 720p se houver limitação do decodificador ou lentidão na central.
- Por ser versão de teste, uma assinatura diferente do APK antigo pode exigir
  reinstalação. Desinstalar apaga rádios e favoritos locais do app anterior.

## O que ainda requer teste real

Os testes em emulador não substituem a Roadstar RS-918BR e o seu telefone.
Captura do display inteiro no Android 17, áudio dos seus aplicativos, rotação,
bloqueio da tela, reconexão, desempenho sustentado e sincronização perceptiva
de áudio/vídeo precisam de validação nesses aparelhos. O resultado de build e
dos testes automatizados será registrado em [test-checklist.md](test-checklist.md).
