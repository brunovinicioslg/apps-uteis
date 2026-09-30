# Publicar na Google Play

Roteiro para o Alumia (`lanterna/`) e o Sossego (`bloqueador/`). Tudo o que a Play Console pede está pronto no repositório; o que só você pode fazer está marcado com **Você**.

## 1. Guarde as chaves (Você, antes de tudo)

Cada app tem uma chave de envio, fora do Git:

- `lanterna/upload-key.jks` e `lanterna/keystore.properties`
- `bloqueador/upload-key.jks` e `bloqueador/keystore.properties`

Copie os quatro arquivos para dois lugares seguros (um pen drive e uma nuvem privada). O `keystore.properties` tem a senha. Sem a chave não dá para enviar atualizações; com a assinatura de apps da Play (o padrão), uma chave perdida pode ser trocada pelo suporte, mas isso leva dias.

## 2. Pacotes

Dentro da pasta do app, com o JDK do Android Studio:

| App | Comando | Pacote gerado |
|---|---|---|
| Alumia | `./gradlew :app:bundleRelease` | `lanterna/app/build/outputs/bundle/release/app-release.aab` |
| Sossego | `./gradlew :app:bundlePlayRelease` | `bloqueador/app/build/outputs/bundle/playRelease/app-play-release.aab` |

Cópias prontas ficam em `publicar/` (fora do Git). O Sossego da Play é o sabor `play`, sem o registro de chamadas (ver o [README do Sossego](bloqueador/README.md#como-funciona)).

## 3. Tipo de conta

- **Organização** (com CNPJ/D-U-N-S): pode lançar direto em produção. Mesmo assim, faça antes um teste interno para instalar pela Play e conferir.
- **Pessoal criada depois de 13/11/2023:** antes de pedir produção, é preciso um **teste fechado com pelo menos 12 testadores inscritos por 14 dias seguidos**. Depois a Play libera o pedido de acesso à produção.

## 4. Criar cada app

Play Console → **Criar app**: nome `Alumia` ou `Sossego`, idioma padrão **Português (Brasil)**, **App**, **Gratuito**, aceite as declarações. O nome completo da loja vem da ficha (passo 6).

## 5. Conteúdo do app (Painel → Configurar seu app)

| Item | Resposta |
|---|---|
| Política de privacidade | Alumia: <https://github.com/brunovinicioslg/apps-uteis/blob/main/lanterna/PRIVACIDADE.md><br>Sossego: <https://github.com/brunovinicioslg/apps-uteis/blob/main/bloqueador/PRIVACIDADE.md> |
| Acesso ao app | Todas as funcionalidades estão disponíveis sem acesso especial (não há login). |
| Anúncios | Não, o app não contém anúncios. |
| Classificação do conteúdo | Categoria de utilitários/outros apps. Responda **Não** a tudo: violência, sexo, linguagem, drogas, apostas, compras, conteúdo gerado por usuários, compartilhamento de localização. O Sossego faz ligações, mas pelo próprio Android: não há troca de conteúdo entre usuários dentro do app. Resultado esperado: Livre. |
| Público-alvo | 18 anos ou mais (pode incluir 13 a 17; **não** marque menos de 13, que traz as regras de apps para crianças). O app não atrai crianças. |
| Segurança dos dados | "Seu app coleta ou compartilha algum dos tipos de dados obrigatórios?" **Não.** Nada sai do celular: os apps não têm permissão de internet. A ficha mostra "Nenhum dado coletado" e "Nenhum dado compartilhado". |
| ID de publicidade | Não usa. |
| Apps governamentais, recursos financeiros, saúde, notícias | Não / nenhum. |
| Permissões de serviço em primeiro plano | Só o Alumia (passo 8). |

O Sossego não precisa de declaração de permissões: a versão da Play não pede registro de chamadas nem SMS, e o papel de filtro de chamadas, contatos, estado do telefone, atender/recusar e fazer ligações não são permissões restritas.

## 6. Ficha da loja

Textos e imagens prontos em `fastlane/metadata/android/<idioma>/` de cada app (`pt-BR` e `en-US`):

| Campo na Play | Arquivo |
|---|---|
| Nome do app | `title.txt` |
| Descrição curta | `short_description.txt` |
| Descrição completa | `full_description.txt` |
| Ícone do app (512×512) | `images/icon.png` |
| Recurso gráfico (1024×500) | `images/featureGraphic.png` |
| Capturas de tela do telefone | `images/phoneScreenshots/` (em ordem) |
| Novidades da versão | `changelogs/1.txt` |

- Categoria: Alumia em **Ferramentas**; Sossego em **Comunicação**.
- Detalhes de contato: um e-mail (aparece na loja; **Você** escolhe qual). Site: a pasta do app no GitHub (`https://github.com/brunovinicioslg/apps-uteis/tree/main/lanterna` ou `.../bloqueador`).
- Adicione a tradução **Inglês (Estados Unidos)** com os arquivos de `en-US`.

As imagens saem das telas de verdade (testes `StoreImagesTest`, comando no README de cada app). Se uma tela mudar, gere de novo.

## 7. Lançar

1. **Teste interno** → Criar versão → aceite a assinatura de apps pela Google Play → envie o `.aab` → cole as novidades → Revisar → Lançar. Em Testadores, crie uma lista com os seus e-mails e abra o link de inscrição no celular.
2. Instale pela Play e confira o básico (**Você**).
3. **Produção** (conta de organização) ou **Teste fechado** (conta pessoal, passo 3): países (Brasil, ou todos), mesma versão, enviar para revisão. A primeira revisão de um app novo pode levar alguns dias.

## 8. Alumia: serviço em primeiro plano (uso especial)

Em Conteúdo do app → **Permissões de serviço em primeiro plano**, marque **Uso especial** e preencha:

**Descrição** (pode colar as duas línguas):

> O Alumia liga e desliga a lanterna quando o usuário chacoalha o celular, também com o app fechado e a tela bloqueada. O serviço em primeiro plano mantém o acelerômetro ativo para reconhecer o gesto no instante em que ele acontece, e mantém a lanterna acesa enquanto o app está em segundo plano (o Android apaga a luz se o processo que a acendeu for encerrado). O serviço só roda depois que o usuário ativa "Chacoalhar para ligar" ou acende a lanterna pelo app, e para quando ele desativa o recurso no app, no botão da notificação ou no bloco das Configurações rápidas. A tarefa não pode ser adiada: se o sistema a interrompesse, o gesto deixaria de funcionar.
>
> Alumia turns the flashlight on and off when the user shakes the phone, including with the app closed and the screen locked. The foreground service keeps the accelerometer listening so the gesture is recognized the moment it happens, and keeps the flashlight on while the app is in the background (Android turns the light off if the process that turned it on is killed). It runs only after the user enables "Shake to turn on" or turns the light on in the app, and stops when the user disables it in the app, from the notification button or from the Quick Settings tile. The task cannot be deferred: if the system stopped it, the gesture would stop working.

**Vídeo (Você):** 30 a 60 segundos, filmando o celular com outro aparelho (a gravação de tela não mostra o flash nem a tela desligada):

1. abrir o Alumia e ligar "Chacoalhar para ligar";
2. voltar à tela inicial e bloquear o celular;
3. chacoalhar: a lanterna acende; chacoalhar de novo: apaga;
4. desativar pelo bloco das Configurações rápidas e mostrar que o gesto não acende mais.

Envie ao YouTube como **Não listado** e cole o link.

## 9. Depois de publicado

- **Celulares com o APK instalado por fora** (`celular/`): a assinatura é outra, então o Android não atualiza pela Play. Desinstale antes de instalar da loja. No Sossego, **exporte as listas antes** (Listas → ⋮ → Exportar) e importe depois.
- **Atualizações:** aumente `versionCode` (e `versionName`) em `app/build.gradle.kts`, escreva `changelogs/<versionCode>.txt`, gere o pacote e envie uma nova versão.

## Fontes

- [Regras de SMS e registro de chamadas](https://support.google.com/googleplay/android-developer/answer/10208820): por que o Sossego da Play não lê o registro de chamadas.
- [Serviços em primeiro plano na Play](https://support.google.com/googleplay/android-developer/answer/13392821): declaração e vídeo.
- [Requisitos de teste para contas pessoais novas](https://support.google.com/googleplay/android-developer/answer/14151465): 12 testadores por 14 dias.
- [Seção Segurança dos dados](https://support.google.com/googleplay/android-developer/answer/10787469): dados processados só no aparelho não precisam ser declarados.
