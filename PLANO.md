# Apps Úteis — Plano geral

Quatro apps **gratuitos e de código aberto**, primeiro para **Android (Google Play)** e depois para **iOS (App Store)** onde for viável.

| Pasta | App | Nome na loja (≤ 30 caracteres) | ID do pacote | Plano detalhado |
|---|---|---|---|---|
| [lanterna/](lanterna/) | **Alumia** | Alumia – Lanterna no Gesto | `io.github.brunovinicioslg.alumia` | [lanterna/PLANO.md](lanterna/PLANO.md) |
| [trena/](trena/) | **Mede Aí** | Mede Aí – Trena pela Câmera | `io.github.brunovinicioslg.medeai` | [trena/PLANO.md](trena/PLANO.md) |
| [gps-relevo/](gps-relevo/) | **Ladeira** | Ladeira – GPS Subida e Descida | `io.github.brunovinicioslg.ladeira` | [gps-relevo/PLANO.md](gps-relevo/PLANO.md) |
| [sms-seguro/](sms-seguro/) | **Sigilo** | Sigilo – SMS Criptografado | `io.github.brunovinicioslg.sigilo` | [sms-seguro/PLANO.md](sms-seguro/PLANO.md) |
| [bloqueador/](bloqueador/) | **Sossego** | Sossego – Bloqueio de Chamadas | `io.github.brunovinicioslg.sossego` | [bloqueador/PLANO.md](bloqueador/PLANO.md) |
| [tijolao/](tijolao/) | **Tijolão** | (não vai para a loja: uso pessoal) | `io.github.brunovinicioslg.tijolao` | [tijolao/PLANO.md](tijolao/PLANO.md) |

---

## 1. Decisões tomadas

| Tema | Decisão | Motivo |
|---|---|---|
| Nomes | Alumia, Mede Aí, Ladeira, Sigilo | Curtos, em português, fáceis de lembrar. Nenhum app com esses nomes foi encontrado na Play (setembro de 2026). "Alumia" vem do verbo *alumiar* (acender, iluminar) e lembra um feitiço de luz sem usar marca de ninguém. "Lumos" e "Lumus" foram descartados: a Warner Bros. pediu o registro de "Lumos", e "Lumus" é marca de uma empresa de óptica para óculos de realidade aumentada. |
| IDs de pacote | `io.github.brunovinicioslg.<app>` | Ligado ao GitHub do projeto e aceito pelo F-Droid. **Nunca muda depois da primeira publicação.** |
| Conta Google Play | **Organização (CNPJ)** | Isenta do teste obrigatório de 12 testadores × 14 dias em cada app. O mesmo número D-U-N-S serve para a conta da Apple depois. |
| Linguagem Android | **Kotlin + Jetpack Compose** | Padrão oficial do Google. Acesso direto a serviços em segundo plano, sensores, ARCore, app de SMS padrão e MapLibre, sem camadas intermediárias (menos bugs de plataforma). |
| Código compartilhado / iOS | **Kotlin Multiplatform (KMP)** para a lógica; interface iOS em **SwiftUI** | A lógica (geometria da trena, cálculo de inclinação, alertas do GPS) é escrita uma vez e testada uma vez. |
| Arquitetura | Camadas `ui` → `domain` → `data`, fluxo de dados unidirecional (MVVM/UDF), Coroutines + Flow | A lógica de negócio fica em Kotlin puro, 100% testável sem aparelho. |
| Banco local | Room (compatível com KMP); no Sigilo, Room + SQLCipher | Migrações testáveis; criptografia do banco no Sigilo. |
| Versões Android | `minSdk 26` (Android 8.0), `targetSdk 36` e `compileSdk 36` | O Google Play exige target 36 para apps novos e atualizações desde 31/08/2026. O mínimo 26 cobre praticamente todos os aparelhos em uso. |
| Monetização | **Grátis, sem anúncios, sem compras e sem rastreadores** | Combina com código aberto e privacidade. O formulário "Segurança dos dados" da Play fica "nenhum dado coletado". Se um dia entrarem anúncios, as declarações de privacidade mudam. |
| GPS: rotas | Em fases: v1 com avisos na via atual, v2 com rotas offline | Entrega valor antes; a navegação completa é a parte mais pesada. |
| GPS: dados de usuários | Locais + OpenStreetMap + compartilhamento por arquivo/QR, **sem servidor** | Custo zero e privacidade total. A arquitetura fica pronta para sincronizar no futuro. |
| Licença | **GPL-3.0**; o Sigilo usa **AGPL-3.0** (exigida pela libsignal) | Garante que versões modificadas continuem abertas. |
| Distribuição | Google Play (principal) + GitHub Releases; F-Droid onde der | O F-Droid não aceita dependências proprietárias (ex.: ARCore), então só alguns apps entram lá. |
| Relatórios de erro | Android Vitals da Play Console (não precisa de SDK) + ACRA opcional, só com consentimento, enviando por e-mail | Sem servidor e sem rastreadores de terceiros. |

## 2. Viabilidade por plataforma (a verdade antes de começar)

| App | Android | iOS |
|---|---|---|
| Alumia | ✅ Completo (com notificação fixa, exigência do Android para rodar em segundo plano) | ⚠️ O iOS não permite detectar o chacoalhar com o app em segundo plano, e a Apple **recusa novos apps de lanterna** sem diferencial forte (regra 4.3(b)). **Recomendação: não lançar no iOS.** |
| Mede Aí | ✅ ARCore (aparelhos compatíveis) + modo alternativo para os demais | ✅ ARKit (+ LiDAR nos iPhone Pro). Precisa se diferenciar do app Medida da Apple. |
| Ladeira | ✅ Completo | ✅ Viável (MapLibre iOS + lógica KMP) |
| Sigilo | ✅ Precisa ser o **app de SMS padrão** (exigência da Play para permissões de SMS) | ❌ **Impossível via SMS**: o iOS não deixa apps enviarem SMS automaticamente nem lerem SMS recebidos. Só daria uma versão "somente internet" (ver o plano do Sigilo). |

## 3. Estrutura de pastas

Cada app é um **projeto Gradle independente**, que pode virar um repositório separado no futuro.

```
C:\dev\apps-uteis\
├── PLANO.md                 ← este arquivo
├── CLAUDE.md                ← convenções e comandos do projeto
├── comum/                   ← padrões copiados para cada app: detekt, lint, modelo de CI, políticas de privacidade
├── lanterna/                ← Alumia
│   ├── PLANO.md
│   └── app/, core-detection/, core-torch/
├── trena/                   ← Mede Aí
│   ├── PLANO.md
│   └── shared/ (KMP), androidApp/, iosApp/ (futuro)
├── gps-relevo/              ← Ladeira
│   ├── PLANO.md
│   └── shared/ (KMP), androidApp/, iosApp/ (futuro), tools/ (pipeline de mapas)
└── sms-seguro/              ← Sigilo
    ├── PLANO.md
    └── crypto/, transport-sms/, mms/, data/, app/
```

**Git:** um repositório (monorepo) no GitHub (`brunovinicioslg/apps-uteis`), com CI separado por pasta (filtros de caminho). O F-Droid aceita apps em subpastas.

## 4. Pré-requisitos

- [x] **Projeto fora do OneDrive, em caminho sem acento nem espaço:** `C:\dev\apps-uteis`.
  - O Android Gradle Plugin no Windows falha com caminhos que têm caracteres fora do ASCII ("Á" de "Área").
  - O OneDrive sincroniza e trava as pastas `build/`, o que causa erros intermitentes.
- [x] JDK: usa o JBR 21 que vem com o Android Studio (`C:\Program Files\Android\Android Studio\jbr`). Android SDK 36.1 instalado.
- [x] GitHub: conta `brunovinicioslg`, já autenticada no computador (`gh`).
- [x] IDs de pacote definidos (tabela no topo).
- [ ] **Conta Google Play de organização (CNPJ):**
  1. **Pedir o número D-U-N-S** (gratuito). Pela ferramenta da Apple (<https://developer.apple.com/enroll/duns-lookup/>) costuma sair em ~5 dias úteis; pela Dun & Bradstreet pode levar até 30 dias. O mesmo número serve para a Play e para a Apple.
  2. Criar a conta de organização na Play Console (US$ 25, pagamento único), com cartão CNPJ, documento de identidade do responsável, e-mail e telefone.
  3. **Verificar um site no Google Search Console.** Pode ser o site da empresa (se o bvcinformatica.com for do CNPJ) ou o site do projeto `brunovinicioslg.github.io`, que vai hospedar as políticas de privacidade.
  4. A verificação de desenvolvedor Android (obrigatória no Brasil desde 30/09/2026) faz parte desse processo.
  5. Na loja aparecem publicamente o nome e os dados de contato da organização.
- [x] **Aparelhos de teste:**
  | Aparelho | ARCore | Barômetro | Papel nos testes |
  |---|---|---|---|
  | **Galaxy S22** | ✅ com API de profundidade | ✅ | AR completo; GPS com barômetro; Samsung (economia de bateria agressiva) |
  | **Redmi Note 12S** | ❌ (não está na lista oficial) | ❌ | Modos alternativos da trena; GPS sem barômetro; Xiaomi/HyperOS (o que mais mata apps em segundo plano) |
  | Emuladores Android 8–16 + Firebase Test Lab + relatório de pré-lançamento da Play | — | — | Versões antigas, Android puro (Pixel), Motorola e outras marcas |
  - **Sigilo:** um chip em cada aparelho testa SMS entre operadoras diferentes; os dois chips num aparelho só testam o dual chip.
  - A versão exata do Android de cada celular é confirmada quando conectarmos por USB (depuração USB).
- [ ] iOS (futuro): um Mac (ou Mac na nuvem/CI), Apple Developer Program (US$ 99/ano; conta de organização com o mesmo D-U-N-S) e um iPhone para testes.

## 5. Processo de qualidade ("zero bugs conhecidos")

Nenhum software sério consegue *garantir* 0 bugs. O que dá para garantir é um processo em que bugs são encontrados **antes** do usuário e nunca voltam depois de corrigidos:

1. **Especificação antes do código.** Cada `PLANO.md` de app tem critérios de aceitação mensuráveis. Uma funcionalidade só está pronta quando cumpre todos.
2. **Arquitetura testável.** Algoritmos (detecção de chacoalhar, geometria, inclinação, criptografia) em Kotlin puro, sem Android. Meta: ≥ 90% de cobertura na camada `domain`.
3. **Testes automatizados em camadas:**
   - unitários (JUnit / kotlin.test) + **testes de propriedade**, que geram milhares de entradas aleatórias;
   - integração (Room, serviços) com Robolectric;
   - interface (Compose UI tests) + **testes de captura de tela** (Roborazzi) nos temas claro e escuro, fonte grande e tela pequena;
   - instrumentados em emuladores do Android 8 ao 16 (Gradle Managed Devices);
   - **testes de reprodução**: dados reais de sensor, GPS e sessões de AR são gravados e reproduzidos de forma determinística no CI (ARCore tem API oficial de gravação e reprodução);
   - **fuzzing** dos decodificadores de mensagens do Sigilo (Jazzer).
4. **Análise estática:** Android Lint com avisos tratados como erro, detekt, ktlint; Renovate/Dependabot + alertas de segurança do GitHub nas dependências.
5. **CI no GitHub Actions:** todo PR roda build, lint, testes e capturas de tela. Uma release gera AAB assinado e build reproduzível, e sobe para a faixa "teste interno" da Play (Gradle Play Publisher).
6. **Aparelhos reais:** Galaxy S22 e Redmi Note 12S + Firebase Test Lab (cota diária gratuita) + **relatório de pré-lançamento** da Play (automático e gratuito a cada upload).
7. **Beta em etapas:** teste interno → teste fechado (voluntário; a conta de organização não é obrigada a fazer, mas ele pega bugs) → teste aberto → produção com **lançamento gradual** (5% → 20% → 50% → 100%), acompanhando o Android Vitals. Meta: **≥ 99,9% de sessões sem falha**.
8. **Acessibilidade e idiomas:** TalkBack, contraste, fonte ampliada; textos em pt-BR + inglês desde o primeiro dia.
9. **Segurança:** modelo de ameaças e **auditoria externa** do Sigilo antes de divulgá-lo como seguro.
10. **Regressão zero:** todo bug corrigido ganha um teste automatizado que reproduz o bug.

## 6. Checklist de publicação na Play (por app)

- [ ] `targetSdk 36`, formato AAB, Play App Signing.
- [ ] **Páginas de memória de 16 KB**: obrigatório para apps com código nativo (MapLibre, ARCore, SQLCipher, libsignal). Usar versões de bibliotecas compatíveis.
- [ ] Política de privacidade publicada (GitHub Pages).
- [ ] Formulário "Segurança dos dados".
- [ ] Classificação indicativa (questionário IARC) e público-alvo.
- [ ] Declarações de permissões sensíveis:
  - Alumia: serviço em primeiro plano do tipo `specialUse`;
  - Ladeira: serviço em primeiro plano do tipo `location`;
  - Sigilo: formulário de permissões de SMS (app padrão).
- [ ] Ficha da loja: ícone 512 px, gráfico de destaque 1024×500, capturas de tela, descrição em pt-BR/en.
- [ ] Nada que imite marcas de terceiros (nome, logo, cores exatas do WhatsApp), por causa da política de falsificação de identidade.

## 7. Ordem de desenvolvimento

Cada app passa por: **prova de conceito dos riscos → MVP → testes → beta → lançamento → melhorias**. O beta de um app roda **enquanto** o próximo é desenvolvido.

| Fase | Conteúdo | Tamanho relativo | Por que nessa ordem |
|---|---|---|---|
| 0 | Fundação: pasta, git + GitHub, CI modelo, lint, conta Play (D-U-N-S) | P | Base para tudo |
| 1 | **Alumia** | P | O app mais simples; valida o processo de publicação e o CI |
| 2 | **Mede Aí** | M | Risco concentrado em AR; primeiro módulo KMP |
| 3 | **Ladeira v1**, depois v2 (rotas) | GG | O maior; precisa do pipeline de mapas |
| 4 | **Sigilo** | G | O mais sensível; exige auditoria antes da divulgação |
| 5 | iOS: Mede Aí e Ladeira; reavaliar a versão "somente internet" do Sigilo | G | Depende da lógica KMP pronta e estável |

Tamanhos: P = pequeno, M = médio, G = grande, GG = muito grande.

## 8. Riscos gerais

| Risco | Mitigação |
|---|---|
| Fabricantes (Xiaomi, Samsung) matam serviços em segundo plano | Tela de ajuda com o passo a passo de cada marca para liberar a bateria; testes no S22 e no Redmi |
| Rejeição na Play por permissões sensíveis | Declarações e vídeos preparados; nenhuma permissão "por via das dúvidas" |
| Aparelhos baratos sem ARCore | Modos alternativos no Mede Aí (testados no Redmi Note 12S) |
| SMS: custo, comportamento das operadoras, MMS instável | Contador de SMS por mensagem; testes por operadora; MMS criptografado marcado como experimental |
| Licenças de dados de mapa e relevo | Atribuições obrigatórias (OpenStreetMap/ODbL, Copernicus) exibidas no app |
| Bug sutil de criptografia | Só bibliotecas auditadas, nada de criptografia caseira, auditoria externa |

## 9. Sugestões extras

- Identidade visual comum (suíte "Apps Úteis") e um site único em `brunovinicioslg.github.io` com as políticas de privacidade. Esse site também pode servir para a verificação no Search Console.
- **Nenhum app pede permissão que não usa.** O Alumia e o Mede Aí nem precisam de internet, o que é um ótimo argumento de privacidade na ficha da loja.
- Link opcional de doação (PIX/GitHub Sponsors) na tela "Sobre"; os apps continuam 100% gratuitos.
- Recursos futuros: Wear OS (lanterna no relógio), Android Auto (GPS), nível de bolha no Mede Aí.

## 10. Decisões pendentes

1. Criar o repositório público `brunovinicioslg/apps-uteis` no GitHub (aguardando confirmação).
2. Idiomas do lançamento (proposta: pt-BR + inglês).
3. Se preferir IDs com o domínio da empresa (ex.: `com.bvcinformatica.*`), decidir **antes da primeira publicação**. Depois disso não muda mais.
4. Antes de publicar, consultar os 4 nomes na busca de marcas do INPI (<https://busca.inpi.gov.br>), classe 9 (software). Registrar as marcas é opcional, mas protege contra cópias.
