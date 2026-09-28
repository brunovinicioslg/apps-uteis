# Apps Úteis — instruções do projeto

Monorepo com 4 apps Android de código aberto (GPL-3.0), cada um em uma pasta com projeto Gradle independente.
Plano geral e decisões: [PLANO.md](PLANO.md). Cada pasta tem seu `PLANO.md` com funcionalidades e **critérios de aceitação**. Uma funcionalidade só está pronta quando cumpre esses critérios.

| Pasta | App | ID do pacote |
|---|---|---|
| `lanterna/` | Alumia | `io.github.brunovinicioslg.alumia` |
| `trena/` | Mede Aí | `io.github.brunovinicioslg.medeai` |
| `gps-relevo/` | Ladeira | `io.github.brunovinicioslg.ladeira` |
| `sms-seguro/` | Sigilo | `io.github.brunovinicioslg.sigilo` |

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
.\gradlew.bat :shared:jvmTest :tools:test  # gps-relevo/ (núcleo e ferramenta de dados)
.\gradlew.bat :core:test                   # sms-seguro/ (protocolo com libsignal real; licença AGPL-3.0)
.\gradlew.bat connectedDebugAndroidTest    # testes instrumentados (emulador ou aparelho conectado)
```

Pacote de dados do Ladeira (em `gps-relevo/`, depois de `.\gradlew.bat :tools:installDist`):
`tools/build/install/tools/bin/tools build --bbox sul,oeste,norte,leste --out build/data/regiao.ldrp` e
`... profile --package build/data/regiao.ldrp --at lat,lon --heading graus --vehicle CAR|TRUCK` para ver o que o app avisaria.

Emulador sem janela (Git Bash): `ANDROID_AVD_HOME="$USERPROFILE/.android/avd" emulator -avd Medium_Phone_API_36.1 -no-window -no-audio -no-boot-anim`.
No Git Bash, caminhos do aparelho com `adb` precisam de `MSYS_NO_PATHCONV=1` (senão `/sdcard` vira caminho do Windows).

Aparelhos de teste do usuário: Galaxy S22 (ARCore + profundidade, barômetro) e Redmi Note 12S (sem ARCore, sem barômetro, HyperOS). Emulador local: `Medium_Phone` (API 36.1).
