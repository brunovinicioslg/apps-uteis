# Tijolão — Plano (emulador de jogos Java)

> ID do pacote: `io.github.brunovinicioslg.tijolao` · **Uso pessoal, não vai para a loja** · Plano geral: [../PLANO.md](../PLANO.md)

## Objetivo

Jogar os jogos Java (J2ME) dos celulares antigos num Android atual sem configurar nada: o APK já traz os jogos, cada um na melhor versão e com a tela certa, e os mais bem avaliados aparecem primeiro.

## Decisões

| Tema | Decisão | Motivo |
|---|---|---|
| Base | Fork do [JL-Mod](https://github.com/woesss/JL-Mod) 0.87.1 (Apache 2.0) | O emulador J2ME mais completo para Android: M3G, Mascot Capsule, MIDI, APIs Nokia/Samsung/Siemens. Reescrever levaria anos. |
| Distribuição | **Só nos aparelhos do usuário**, APK gerado localmente | Os jogos são comerciais (EA, Gameloft…). O código fica no repositório público; os jogos, **nunca** (`javagames/` no `.gitignore`). |
| Avaliações | Só no celular (SharedPreferences) | Sem servidor e sem conta. |
| Licença do código | Apache 2.0 (a do JL-Mod), diferente do resto do repositório | Obrigação da licença de origem. |
| Internet | Nenhuma | Nada para enviar; menos superfície de ataque. |

## Status (28/09/2026)

**v0.1.0 pronta para instalar.** Catálogo com 608 jogos (de 1.234 arquivos), 9 testes do montador passando e Lint sem erros.

**Verificado no emulador (Android 16), no APK de publicação (R8):**
- biblioteca com ícones, busca, ordem por estrelas/A–Z/recentes e "jogados recentemente";
- dar e tirar estrelas; o de 5 estrelas passa para o topo;
- primeira abertura converte o jogo (menos de 2 s), as seguintes abrem direto;
- 3D M3G (Crazy Taxi 3D), 3D Mascot Capsule (Blades e Magic 3D), jogo deitado (Nitro Street Racing 2), multi-resolução (Johnny Bravo);
- sair do jogo pelo menu volta para a biblioteca;
- ajustes do jogo e do emulador; adicionar um `.jar` pelo seletor do sistema.

**Falta verificar nos aparelhos reais (Galaxy S22 e Redmi Note 12S):**
- desempenho e som;
- teclado virtual na tela real;
- jogos pesados em 3D.

## Funcionalidades

### v0.1 (feito)

1. **Catálogo montado na compilação** (`buildSrc/`): um jogo por título, na versão 240x320 > multi > 176x220 > 128x160 (320x240 só se for a única). Arquivos do mesmo jogo com nomes diferentes são juntados; edições diferentes (número, 2D/3D, Touch), não.
2. **Leitura tolerante** de jars estragados (manifesto fora do padrão, índice quebrado, nomes em Latin-1), com reempacotamento.
3. **Biblioteca** (Compose): grade com ícones, busca sem acentos, ordens, avaliação de 1 a 5 estrelas, "jogados recentemente".
4. **Jogar**: conversão na primeira vez, configuração automática de tela e orientação, processo separado para o jogo.
5. **Por jogo**: ajustes do JL-Mod e "apagar progresso" (os saves).
6. **Adicionar outros `.jar`** pelo seletor de arquivos do sistema (sem permissão de armazenamento).

### Sugestões para depois

- Filtros por fabricante e por "3D", e uma aba de favoritos.
- Escolher a versão do jogo (ex.: a 320x240 em vez da 240x320) na ficha do jogo.
- Backup das estrelas e dos saves num arquivo.
- Controles físicos (Bluetooth) mapeados por padrão.
- Atualizar as dependências do JL-Mod (AGP 9, Compose mais novo, `targetSdk 36`) e alinhar as bibliotecas nativas a 16 KB (hoje o ffmpeg não é; só importa em aparelhos com páginas de 16 KB).

## Critérios de aceitação

- [x] Instalar o APK e jogar qualquer jogo da lista sem configurar nada.
- [x] Os jogos mais bem avaliados aparecem primeiro (ordem padrão).
- [x] Nenhum jogo no repositório público.
- [x] APK de publicação (R8) testado: catálogo, jogo 2D, M3G, Mascot Capsule, ajustes, importação.
- [x] Sem permissões de internet, armazenamento, localização, microfone ou Bluetooth.
- [ ] Testado no Galaxy S22 e no Redmi Note 12S.
