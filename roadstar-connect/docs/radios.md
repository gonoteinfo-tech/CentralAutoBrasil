# Rádios incluídas na versão 0.1.1

Seleção de emissoras de música, notícias e esportes. Não representa um ranking de audiência.

As fontes abaixo identificam as emissoras. Os endereços diretos ficam em
[radio_catalog.json](../app/src/main/res/raw/radio_catalog.json). A transmissão é da própria rádio, via internet.

## Anápolis

- [96 FM 96,3 — Anápolis (GO)](https://radio96fm.net/)
- [São Francisco 97,7 FM — Anápolis (GO)](https://radiosaochico.com.br/radio-ao-vivo-e-online-player/)
- [Manchester 93,3 FM — Anápolis (GO)](https://www.radiomanchester.com.br/fm/)

## Goiás

- [Cultura 101,1 FM — Catalão (GO)](https://radioculturaonline.com.br/)
- [Alpha FM 102,1 — Goiânia (GO)](https://alphagoiania.com.br/)
- [89 A Rádio Rock — Goiânia (GO)](https://89goiania.com.br/89-goiania/)
- [CBN — Goiânia (GO)](https://github.com/junguler/m3u-radio-music-playlists/blob/main/allradio.net/Brazilian-portuguese.m3u)
- [Terra FM 104,3 — Goiânia (GO)](https://radioterrafm.com.br/)

## Brasil

- [CBN — São Paulo (SP)](https://cbn.globoradio.globo.com/servicos/mobile/MOBILE.htm)
- [BandNews FM — São Paulo (SP)](https://www.band.com.br/radios/bandnews-fm)
- [Band FM — São Paulo (SP)](https://www.band.com.br/band-fm)
- [Nativa FM — São Paulo (SP)](https://www.band.com.br/radios/nativa/sao-paulo)
- [Mix FM — São Paulo (SP)](https://aovivo.radiomixfm.com.br/)
- [Antena 1 — São Paulo (SP)](https://www.antena1.com.br/ouvir-radio-online)
- [Alpha FM — São Paulo (SP)](https://alphafm.com.br/)
- [Itatiaia — Belo Horizonte (MG)](https://www.itatiaia.com.br/)
- [Gaúcha — Porto Alegre (RS)](https://gauchazh.clicrbs.com.br/)
- [JBFM — Rio de Janeiro (RJ)](https://jb.fm/)
- [89 FM A Rádio Rock — São Paulo (SP)](https://www.radiorock.com.br/)
- [Kiss FM — São Paulo (SP)](https://kissfm.com.br/aovivo/)

## Atualização do catálogo

O catálogo é instalado uma vez. Rádios existentes e favoritos são preservados;
uma estação excluída não é adicionada novamente a cada abertura. IDs e URLs já
cadastrados não são duplicados. Favoritos começam vazios para você escolher.

As emissoras podem mudar de endereço ou ficar fora do ar. A checagem de uma
amostra confirma áudio naquele momento, sem garantir disponibilidade contínua
ou substituir testes no telefone e na central.

A Rádio Imprensa foi deixada fora desta seleção porque o endpoint localizado
recusou conexão. A Manchester usa o endereço atual identificado no site oficial.

## Checagem das transmissões

Em 17/10/2026, a [execução 39072774934](https://github.com/gonoteinfo-tech/CentralAutoBrasil/actions/runs/39072774934)
detectou áudio nas 20 transmissões por HTTPS. As amostras foram examinadas pelo
FFprobe: AAC ou MP3, incluindo os streams HLS. O arquivo de relatório está
no artefato `Roadstar-radio-stream-check` dessa execução. Não houve teste de
reprodução no Android 17 ou na Roadstar física.
