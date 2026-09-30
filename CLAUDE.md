# Apps Úteis — instruções do projeto

Monorepo com 4 apps Android de código aberto (GPL-3.0), cada um em uma pasta com projeto Gradle independente.
Plano geral e decisões: [PLANO.md](PLANO.md). Cada pasta tem seu `PLANO.md` com funcionalidades e **critérios de aceitação**. Uma funcionalidade só está pronta quando cumpre esses critérios.

| Pasta | App | ID do pacote |
|---|---|---|
| `lanterna/` | Alumia | `io.github.brunovinicioslg.alumia` |
| `trena/` | Mede Aí | `io.github.brunovinicioslg.medeai` |
| `gps-relevo/` | Ladeira | `io.github.brunovinicioslg.ladeira` |
| `sms-seguro/` | Sigilo | `io.github.brunovinicioslg.sigilo` |
| `bloqueador/` | Sossego (bloqueador de chamadas) | `io.github.brunovinicioslg.sossego` |
| `tijolao/` | Tijolão (uso pessoal, fork do JL-Mod) | `io.github.brunovinicioslg.tijolao` |

## Convenções

- O usuário fala português (pt-BR). Documentação e textos do app em pt-BR, com tradução em inglês (`values-en`).
- Código (identificadores e comentários) em inglês.
- Kotlin + Jetpack Compose, AGP 9 com Kotlin embutido (não aplicar `org.jetbrains.kotlin.android` nos módulos), `minSdk 26`, `targetSdk 36`.
- Lógica de negócio em módulos Kotlin puros (sem Android), cobertos por testes unitários.
- Nunca declarar permissões que o app não usa. Nenhum SDK de anúncios, analytics ou rastreamento.
- Todo bug corrigido ganha um teste que o reproduz.

## Comandos (Windows)

O JDK é o JBR do Android Studio. Em PowerShell, antes do Gradle:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

Dentro da pasta do app (ex.: `lanterna/`):

```powershell
.\gradlew.bat test lint assembleDebug      # lanterna/: testes unitários, lint e APK de depuração
.\gradlew.bat :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug   # trena/
.\gradlew.bat :shared:jvmTest :tools:test :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug   # gps-relevo/
.\gradlew.bat :core:test :core:lint :app:testDebugUnitTest :app:lintDebug :app:assembleDebug   # sms-seguro/ (AGPL-3.0)
.\gradlew.bat :core:test :app:testPlayDebugUnitTest :app:testFullDebugUnitTest :app:lintPlayDebug :app:lintFullDebug :app:assembleFullDebug   # bloqueador/
.\gradlew.bat connectedDebugAndroidTest    # testes instrumentados (emulador ou aparelho conectado)
```

Pacote de dados do Ladeira (em `gps-relevo/`, depois de `.\gradlew.bat :tools:installDist`):
`tools/build/install/tools/bin/tools build --bbox sul,oeste,norte,leste --out build/data/regiao.ldrp` (área pequena, pelo Overpass) ou
`... build --pbf minas-gerais.osm.pbf --out build/data/minas-gerais.ldrp [--bbox ...]` (estado inteiro, com `JAVA_OPTS=-Xmx16g`;
o arquivo do estado vem de `download.openstreetmap.fr/extracts/south-america/brazil/southeast/`), `... info --package x.ldrp` e
`... profile --package build/data/regiao.ldrp --at lat,lon --heading graus --vehicle CAR|TRUCK` para ver o que o app avisaria.
`... track --package ... --at lat,lon --heading graus --out trajeto.csv` gera um trajeto simulado (uma posição por segundo) e
`... drive --package ... --track trajeto.csv --vehicle TRUCK` mostra o que o app falaria ao longo dele.

Ladeira no emulador (quase sem espaço): `.\gradlew.bat :androidApp:assembleDebug -Pabi=x86_64` gera um APK de 28 MB em vez de 64 MB.
As regiões ficam em `files/regions/<nome>/{roads.ldrp,map.pmtiles}` (copiar com `adb push` para `/data/local/tmp` e `run-as`).
GPS simulado: `adb emu geo fix <lon> <lat> <altitude> 8 <nós>` (o emulador informa a velocidade, mas a direção sempre 0°).
Mapa: `pmtiles extract https://build.protomaps.com/AAAAMMDD.pmtiles regiao.pmtiles --bbox=oeste,sul,leste,norte --maxzoom=15`
(para um estado, `--region=estado.geojson` com o contorno; MG até o zoom 15 = 585 MB, zoom 14 = 325 MB).

Emulador sem janela (Git Bash): `ANDROID_AVD_HOME="$USERPROFILE/.android/avd" emulator -avd Medium_Phone_API_36.1 -no-window -no-audio -no-boot-anim`.
No Git Bash, caminhos do aparelho com `adb` precisam de `MSYS_NO_PATHCONV=1` (senão `/sdcard` vira caminho do Windows).

APKs para os celulares do usuário: pasta `celular/` (fora do Git, tem os jogos do Tijolão e os mapas), com `LEIA-ME.txt`.
Versões de publicação (`assembleRelease`, `-Pabi=arm64-v8a` onde existe) assinadas com a chave de depuração do PC
(`apksigner sign --ks %USERPROFILE%\.android\debug.keystore --ks-pass pass:android`); o Tijolão usa a chave própria dele.

Aparelhos de teste do usuário: Galaxy S22 (ARCore + profundidade, barômetro) e Redmi Note 12S (sem ARCore, sem barômetro, HyperOS). Emulador local: `Medium_Phone` (API 36.1).

Sigilo (`sms-seguro/`):
- Precisa do NDK 30.0.16248370 em `Sdk/ndk/` só para remover os símbolos de depuração da libsignal (senão o APK passa de 100 MB).
- `-Pabi=x86_64` para o emulador; `-PallowScreenshots` libera capturas de tela (o app as bloqueia) só para testar a interface.
- Sempre testar também o APK de publicação (R8): `assembleRelease`, `zipalign` e `apksigner` com a chave de depuração, e um convite aceito no emulador. Foi assim que apareceu a quebra da libsignal pelo R8.
- "Segundo celular" no computador: `.\gradlew.bat :peer:installDist`, depois `peer/build/install/peer/bin/peer invite|send|ack|receive --state pasta ...`. Os SMS entram no emulador com `adb emu sms send <número> <texto>`; os que o app envia aparecem no log (`adb logcat -s SigiloSms`, só em depuração, sempre cifrados).
- O emulador tem só 6 GB de dados e fica perto do mínimo: desinstale antes de reinstalar (`adb uninstall`), e para voltar ao Ladeira ou ao Alumia reinstale-os.

Google Play (Alumia e Sossego; roteiro completo em `PUBLICAR.md`):
- Pacotes: `lanterna/`: `:app:bundleRelease`; `bloqueador/`: `:app:bundlePlayRelease`. Assinados com a chave de envio de `keystore.properties` + `upload-key.jks` na pasta do app (fora do Git; sem eles o release sai sem assinatura). Cópias para enviar em `publicar/` (fora do Git).
- Textos e imagens da loja em `<app>/fastlane/metadata/android/<pt-BR|en-US>/`. As imagens saem das telas reais: `StoreImagesTest` com `--rerun -PstoreImages=<pasta>` (sem a propriedade o teste é pulado); Robolectric com gráficos nativos e SDK 30 (cores próprias do app).
- Os APKs de `celular/` continuam com a chave de depuração do PC (reassinar o release com `apksigner`), para atualizar o que já está nos celulares.
- Políticas de privacidade: `<app>/PRIVACIDADE.md`. O link "Ver o código-fonte" de cada app abre a pasta dele no GitHub.

Sossego (`bloqueador/`):
- `minSdk 29` (o papel de filtro de chamadas só existe a partir do Android 10).
- Sabores: `play` (Google Play, sem `READ_CALL_LOG`: a aba vira "Histórico" só com os bloqueios) e `full` (APK dos celulares, com o registro de chamadas). `BuildConfig.CALL_LOG` separa os dois; a permissão fica em `app/src/full/AndroidManifest.xml`.
- `TabBarTest` mede de verdade (gráficos nativos) se cada rótulo das abas cabe numa linha a 360 dp; nome de aba novo precisa passar nele.
- No emulador, ligações de verdade: `adb emu gsm call <número>`; o papel sem a tela do sistema: `adb shell cmd role add-role-holder android.app.role.CALL_SCREENING io.github.brunovinicioslg.sossego`; resultado no log (`SCREENING_COMPLETED`).
- Robolectric não estabiliza um campo de texto dentro de um diálogo em telas de 411dp ou mais: os testes do diálogo de número usam a tela padrão (`ListDialogTest`). No aparelho funciona.
