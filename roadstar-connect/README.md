# Roadstar Connect

App Android nativo instalado **no telefone**, com layout escuro, detalhes
verde-água e quatro abas: Início, Rádios, Navegador e Favoritos.

O projeto inclui compilação automatizada pelo GitHub Actions, com testes,
análise do código e verificação da assinatura do APK. Consulte
[a validação](docs/test-checklist.md) para os resultados e limites dos testes.
A imagem conceitual não é uma captura de um app em execução.

**APK 0.1.0 gerado com sucesso:**
[baixar o ZIP](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39071846421/artifacts/12115413223)
ou consultar [a execução verificada](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39071846421).

## O que cada tela oferece

- **Início:** acesso a rádios, YouTube, Maps/Waze, música e ajuda de conexão.
- **Rádios:** cadastro de uma transmissão HTTPS direta, edição e exclusão.
- **Favoritos:** seleção de rádios preferidas, persistidas no telefone.
- **Navegador:** site oficial do YouTube no telefone, com voltar/avançar,
  recarregar, início, tela cheia e opção para abrir externamente.

No Android Auto, a Roadstar recebe somente o catálogo e os controles de
**áudio**. O navegador e os vídeos **não aparecem na central**. O YouTube pode
exigir o navegador externo ou o app oficial para login e determinados vídeos.
Não há garantia de reprodução de todos os conteúdos. Vídeos devem ser usados
com o veículo estacionado; o app não detecta o movimento do carro.

O catálogo começa vazio. Adicione o endereço HTTPS direto fornecido pela rádio,
como um stream MP3/AAC ou uma playlist HLS. Uma página web, playlist PLS ou link
`youtube.com/watch` não equivale a um stream de áudio reproduzível. Endereços
com usuário/senha embutidos são rejeitados. O app não controla o rádio FM físico
da multimídia e usa a internet do telefone para transmissões online.

## Baixar o APK pelo GitHub

O projeto está na pasta `roadstar-connect/` do repositório
[CentralAutoBrasil](https://github.com/gonoteinfo-tech/CentralAutoBrasil/tree/codex/roadstar-connect-apk/roadstar-connect),
na branch `codex/roadstar-connect-apk`.

1. Abra [as compilações do Roadstar Connect](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/workflows/roadstar-connect-apk.yml).
2. Escolha uma execução concluída com sucesso e baixe o artefato
   **Roadstar-Connect-APK**. O download pelo GitHub pode exigir login.
3. Extraia o ZIP e instale `Roadstar-Connect-0.1.0-debug.apk` no telefone.
   O arquivo `SHA256SUMS.txt` permite conferir a integridade do APK.

O workflow fica na raiz do repositório, em
`.github/workflows/roadstar-connect-apk.yml`, e compila a pasta deste app.
Usa Gradle 8.13 instalado pela action; não depende do wrapper ausente.
Os downloads ficam disponíveis por 30 dias após a execução.

## Compilar em um computador com Android Studio

1. Use um JDK completo 17 ou 21, Android Studio compatível com AGP 8.13.2,
   Android SDK Platform 36, Build-Tools 36.0.0 e Platform-Tools.
2. Abra esta pasta como projeto Android. Defina `ANDROID_HOME` para seu SDK
   ou deixe o Android Studio criar `local.properties` com `sdk.dir`.
3. Este pacote não contém `gradle-wrapper.jar`: o download não foi possível
   no ambiente de criação. Em Linux/macOS com curl e Python 3, execute:

   ```sh
   sh tools/setup-wrapper.sh
   sh gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

   O script baixa o JAR do repositório oficial Gradle e verifica seu SHA-256
   com o checksum oficial. Também fixa o checksum da distribuição Gradle 8.13.
   Não ignora certificados nem altera configurações de rede.

   No Windows, use Git Bash/WSL para esse preparo, ou um Gradle 8.13 oficial
   instalado para executar `gradle wrapper --gradle-version 8.13`. Depois:

   ```bat
   gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

4. O APK de teste, **após build concluído**, ficará em:
   `app/build/outputs/apk/debug/app-debug.apk`.
5. Instale no telefone por Android Studio ou:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Um APK de depuração usa a chave local gerada pelo Android SDK. Esta entrega
não publica o app nem gera assinatura para distribuição na Play Store.

## Testes sem Android SDK

Com um Java que inclua o módulo de compilação `jdk.compiler`:

```sh
sh tools/check-core.sh
```

Isso executa a validação real das URLs Java. Não testa a reprodução, a WebView,
as telas Android ou a integração física com a Roadstar.

## Conectar ao Android Auto

1. Abra o app no telefone e adicione uma rádio válida.
2. Conecte o telefone à Roadstar pelo método Android Auto disponível na central.
3. Procure **Roadstar Connect** entre os aplicativos de mídia.
4. Para APKs de teste instalados fora da Play Store, a documentação do Google
   prevê a opção de desenvolvedor **Fontes desconhecidas** para apps de mídia.
   Essa disponibilidade depende da versão do Android Auto. Outra alternativa
   oficial de testes é uma origem de distribuição de testes da Google Play.

Instalar o APK no telefone, sozinho, não comprova sua descoberta no Android
Auto. A compatibilidade final precisa de teste com seu telefone e sua central
Roadstar RS-918BR. Não houve acesso físico a eles durante o desenvolvimento.

## Projeto e decisões

- Java 17, minSdk 28, compile/targetSdk 36; Media3 1.8.0; Material 1.13.0.
- Catálogo em SharedPreferences; sem servidor, cadastro ou credenciais Google.
- Browser dedicado ao YouTube; páginas externas seguem para o app apropriado.
- Sair do navegador destrói sua WebView para interromper mídia oculta e
  preserva a última URL conhecida para recarga. Não retoma a rádio sozinho.
- O estado visual adapta a barra do player e o navegador à orientação horizontal.
- Revisão e limitações conhecidas: [registro de validação](docs/test-checklist.md).

## Referências oficiais

- [Mídia para Android Auto](https://developer.android.com/training/cars/media)
- [Categorias de aplicativos](https://developer.android.com/training/cars#supported-app-categories)
- [Teste em veículos e APKs de mídia](https://developer.android.com/training/cars/testing)
- [Compatibilidade AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Verificação do Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:verification)
