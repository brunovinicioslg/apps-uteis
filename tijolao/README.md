# Tijolão

Emulador de jogos Java (J2ME) para Android, com uma biblioteca de jogos embutida no APK: instalou, é só escolher e jogar.

- Biblioteca com busca (ignora acentos) e três ordens: **mais bem avaliados**, A–Z e recentes, além de uma fileira "jogados recentemente".
- Avaliação de 1 a 5 estrelas, guardada só no celular. Tocar de novo na mesma estrela tira a nota.
- Cada jogo abre na melhor versão que existe e já configurado: tamanho de tela certo e tela deitada nos jogos feitos para 320x240.
- Jogos 3D (M3G/JSR-184 e Mascot Capsule v3), som MIDI e teclado virtual.
- Outros `.jar` podem ser adicionados pelo menu ⋮ → "Adicionar seus jogos (.jar)".
- **Multiplayer por Bluetooth** (35 jogos da coleção: PES, Real Football, Tekken, Snake 3, Pokémon…) entre dois celulares com o Tijolão. Um cria a partida e o outro entra.
- Sem internet: o app não tem essa permissão e não envia nada.

## Os jogos não estão neste repositório

Os jogos são de terceiros (EA, Gameloft e outros). Este repositório tem **só o código**. O APK com jogos é gerado no computador a partir de uma pasta de `.jar` e é **só para uso pessoal**: não publique nem distribua esse APK.

A pasta padrão é `../javagames` (ao lado desta), ignorada pelo Git. Sem a pasta, o app sai sem jogos, só com a opção de adicionar.

## Como gerar

O JDK é o JBR do Android Studio. Em PowerShell, na pasta `tijolao/`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat -p buildSrc test                                         # testes do montador do catálogo
.\gradlew.bat :app:assembleEmulatorRelease -Pabi=arm64-v8a             # APK para celulares (todos os jogos, ~400 MB)
.\gradlew.bat :app:assembleEmulatorDebug -Pabi=x86_64 "-PgamesOnly=crazy taxi,tetris"   # teste no emulador
```

O APK sai em `app/build/outputs/apk/emulator/<debug|release>/`.

| Opção | Efeito |
|---|---|
| `-PgamesDir=C:\pasta` | Outra pasta de jogos |
| `-PgamesOnly=a,b` | Só os títulos que contêm essas palavras |
| `-PgamesLimit=10` | Só os 10 primeiros títulos (ordem alfabética) |
| `-Pabi=arm64-v8a` | Só o APK dessa arquitetura (sem isso saem 5, cada um com todos os jogos) |

**Assinatura:** o APK de publicação usa `tijolao-release.jks` e `keystore.properties`, que ficam nesta pasta e fora do Git. **Guarde uma cópia dos dois.** O Android só aceita atualizar o app com a mesma chave. Sem ela, é preciso desinstalar, e as estrelas e os saves se perdem.

## Como o catálogo é montado

Na compilação, `buildSrc` lê todos os `.jar` da pasta (inclusive subpastas) e gera `catalog.json`, os jogos e os ícones dentro do APK:

- **Um jogo por título.** A versão escolhida segue esta ordem: 240x320, multi-resolução, 176x220, 128x160. A de 320x240 (deitada) só entra se não existir outra.
- **Nomes diferentes para o mesmo jogo** ("Beowlf"/"Beowulf", "Jhonny"/"Johnny") são juntados quando os arquivos declaram o mesmo jogo (nome e fabricante, ou a mesma classe principal) e os títulos diferem só na grafia. Nunca quando muda um número, "2D/3D" ou "Touch", que indicam outra edição.
- **Arquivos meio estragados** (manifesto fora do padrão, índice do zip quebrado, nomes em outra codificação) são lidos com tolerância, como os celulares faziam, e reempacotados. Os ilegíveis ficam de fora, com aviso no log da compilação.

## Multiplayer por Bluetooth

Os dois celulares precisam do Tijolão. No jogo, um escolhe criar a partida (o Android pergunta se pode deixar o celular visível) e o outro procura e entra. Na primeira vez, o app pede a permissão "Dispositivos por perto" e para ligar o Bluetooth.

Se a permissão for recusada duas vezes, o Android para de perguntar e os jogos dizem que não há Bluetooth. Para liberar de novo: Configurações → Apps → Tijolão → Permissões → Dispositivos por perto.

No primeiro "Jogar", o jogo é convertido para o formato do Android (1 a 2 segundos) e ganha a configuração de tela. Das próximas vezes abre direto.

## Base: JL-Mod

O Tijolão é derivado do [JL-Mod](https://github.com/woesss/JL-Mod), um fork do [J2ME Loader](https://github.com/nikita36078/J2ME-Loader), sob a [licença Apache 2.0](LICENSE). As bibliotecas de terceiros e suas licenças estão em `app/src/main/assets/licenses.html` (tela "Sobre").

Mudanças em relação ao JL-Mod 0.87.1:

- Nova tela inicial (a biblioteca, em Jetpack Compose) e o catálogo embutido (`buildSrc/`, pacote `io.github.brunovinicioslg.tijolao`).
- Sem internet: removidos o envio de relatórios de erro (ACRA/AppCenter), as doações e a abertura de links http de `.jad`. Permissões: vibrar, criar atalho e Bluetooth (dispositivos por perto).
- Tudo fica na pasta interna do app. Não pede acesso ao armazenamento: para adicionar jogos, usa o seletor de arquivos do sistema.
- `Canvas.isShown()` responde "visível" entre `setCurrent()` e o aparecimento da tela, como nos celulares. Sem isso, o Crazy Taxi 3D fechava ao abrir.
- Compila com o NDK 30 (sem `LOCAL_ARM_NEON`, correções de tipo no M3G). O TinySoundFont (MIT) vem incluído no código.
- Bluetooth: o pedido de permissão espera a sua resposta (antes, a primeira tentativa sempre falhava), a conexão sempre passa pelo pedido de permissão e de ligar o Bluetooth, e a espera por "ligar o Bluetooth?" não acorda antes da resposta.
- `minSdk 26`.

Para os recursos do emulador (shaders, bancos de som SF2/DLS, skins, propriedades do Mascot Capsule), veja o [README do JL-Mod](https://github.com/woesss/JL-Mod#readme). Tudo isso continua disponível em ⋮ → "Ajustes do emulador" e nos ajustes de cada jogo.
