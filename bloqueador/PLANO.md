# Sossego — Plano (bloqueador de chamadas)

> ID do pacote: `io.github.brunovinicioslg.sossego` · Nome na loja: **Sossego – Bloqueio de Chamadas** · Plano geral: [../PLANO.md](../PLANO.md)

## Objetivo

Bloquear chamadas indesejadas em silêncio: por padrão o app só bloqueia, sem notificar. Modos para bloquear uma lista negra, deixar passar só uma lista branca ou bloquear tudo, sem internet e sem coletar nada.

## Decisões

| Tema | Decisão | Motivo |
|---|---|---|
| Como bloqueia | Papel de **filtro de chamadas** do Android (`CallScreeningService`, `ROLE_CALL_SCREENING`) | Qualquer app pode ter esse papel desde o Android 10, sem substituir o app Telefone. A Play aceita. |
| Versão mínima | Android 10 (`minSdk 29`), diferente dos outros apps (26) | O papel de filtro para apps comuns só existe a partir do Android 10. |
| Contatos | O filtro de um app que não é o Telefone **não recebe ligações de contatos** (regra do Android, conferida no emulador com Android 16: "contact exists", sem acionar o filtro) | Na lista branca é ótimo: contato sempre passa. Na lista negra, um contato só se bloqueia pelos números bloqueados do Android (o app abre a tela) ou pelo "Bloquear tudo". |
| Bloquear tudo | Filtro para quem não é contato + recusa ao tocar (`TelecomManager.endCall`, obsoleto mas funcional) para contatos | É o único jeito sem ser o app Telefone. Pede "estado do telefone" e "atender/recusar chamadas" só ao ligar esse modo. No emulador, recusou 75 ms depois de começar a tocar. |
| Números | Regras próprias para números brasileiros (`PhoneNumbers`), sem libphonenumber | Cobrem +55, 0 + DDD, código de operadora, 0800/0303, nono dígito antigo; testadas uma a uma. O APK fica com 2,8 MB. |
| Dados | Listas e histórico em SQLite (Android, sem biblioteca); preferências em DataStore | Consultas por índice em milissegundos, transações, e cópia de segurança do sistema. |
| Falhas | Qualquer erro deixa a chamada passar | Um defeito nunca pode sumir com ligações. |
| Internet | Nenhuma | Nada para enviar. |

## Funcionalidades

### v0.1 (feito, 29/09/2026)

1. **Modos:** desligado, lista negra (padrão), só lista branca, bloquear tudo (com confirmação).
2. **Ao bloquear:** recusar (padrão; sem tocar e sem notificação de chamada perdida) ou silenciar (chega sem som e vira chamada perdida). Registrar no histórico do telefone: ligado por padrão.
3. **Notificações:** nenhuma (padrão), uma por bloqueio ou resumo do dia (uma só, com a contagem). Sempre silenciosas.
4. **Listas:** número exato ou "começa com" (0303, 4003, +1), com nome; adicionar digitando, pelos contatos (sem pedir permissão de contatos) ou pelo histórico; editar, apagar (com desfazer). O mesmo número nas duas listas: a última escolha vale (o app move).
5. **Filtros da lista negra:** telemarketing 0303 (ligado por padrão), números ocultos, ligações do exterior.
6. **Quem insiste:** na lista branca, o mesmo número ligando de novo em até 3 minutos passa (ligado por padrão).
7. **Horário:** outro modo num intervalo (ex.: só a lista branca das 22h às 7h), por dias da semana; intervalos que passam da meia-noite pertencem ao dia em que começam.
8. **Histórico** dos bloqueios (os 500 últimos), com liberar, bloquear sempre, copiar e apagar.
9. **Bloco nas configurações rápidas** liga e desliga o bloqueio, voltando ao modo escolhido.
10. **Exportar e importar** as listas num arquivo `;` que abre em planilha.
11. **Avisos contextuais:** ativar o filtro, permissões do "Bloquear tudo", acesso aos contatos na lista branca, bateria em Xiaomi e Samsung.

### Etapa A: ligar pelo app (feito, 30/09/2026)

Abas: **Teclado · Recentes · Contatos · Bloqueio · Listas**. O app abre na última aba usada (na primeira vez, em Bloqueio).

1. **Teclado:** número formatado enquanto se digita; contatos sugeridos pelas letras (6274 = MARIA) ou pelos dígitos; segurar o 0 põe "+", segurar o 1 liga para a caixa postal; apagar (segurando, apaga tudo); colar; o botão verde sem nada digitado traz de volta o último número discado.
2. **Recentes:** o registro de chamadas do telefone junto com os bloqueios do app, cada chamada uma vez (o bloqueio substitui a linha que o telefone escreveu, com o motivo). Filtros: todas, perdidas, recebidas, feitas, bloqueadas. Ligar de volta, liberar, bloquear, copiar.
3. **Contatos:** busca sem acento, favoritos primeiro; cada contato com seus números para ligar ou bloquear, e atalho para editar no app Contatos.
4. **Ligação:** feita pelo Android (`TelecomManager.placeCall`), aparece na tela de chamada do próprio celular. Dois chips: se o Android estiver em "perguntar sempre", o app pergunta. Sem a permissão de ligar, abre o app Telefone com o número digitado.
5. **Permissões novas**, pedidas só na hora: fazer ligações, contatos e, fora da Play, registro de chamadas.

### Google Play (30/09/2026)

- A Play só libera o registro de chamadas para bloqueadores com "histórico comprovado de proteção significativa" (relatórios de analistas, prêmios), que um app novo não tem. Por isso há dois sabores: **`play`**, sem `READ_CALL_LOG` (a aba vira **Histórico**, só com os bloqueios, e o rediscar lembra o último número ligado pelo app), e **`full`**, o APK dos celulares, com o registro.
- Encontrado pelas imagens da loja: "Bloqueadas" não cabia na aba a 360 dp (quebrava em "Bloqueada/s"). Rótulo trocado por "Histórico" (título "Chamadas bloqueadas"), rótulos limitados a uma linha e `TabBarTest` medindo cada rótulo em português e inglês.
- Versão 1.0.0; pacote `:app:bundlePlayRelease`; textos, imagens e política de privacidade prontos (ver [../PUBLICAR.md](../PUBLICAR.md)).

### Etapa B (planejada): modo Telefone

O mesmo app como **app Telefone padrão**, opcional: o filtro passa a receber todas as ligações (contatos também), o "Bloquear tudo" deixa de tocar por um instante, e o histórico mostra recebidas, perdidas, recusadas, bloqueadas e feitas. Exige teclado, tela de chamada (recebendo, em andamento, espera, conferência, Bluetooth, sensor de proximidade), contatos e caixa postal. Não substitui a gravação de chamadas do Telefone da Samsung (apps de terceiros não gravam desde o Android 10).

## Critérios de aceitação

- [x] Uma ligação da lista negra é recusada sem tocar, qualquer que seja o formato do número (verificado no emulador: +55, com e sem DDD).
- [x] Telemarketing 0303 recusado por padrão (emulador, depuração e publicação/R8).
- [x] Número comum toca normalmente no modo lista negra (emulador).
- [x] Só lista branca: desconhecido recusado, contato toca, quem insiste passa (emulador).
- [x] Bloquear tudo: desconhecido recusado pelo filtro, contato recusado ao tocar; voltando à lista negra, o contato toca de novo (emulador).
- [x] Chamadas liberadas não vão para o histórico; as bloqueadas aparecem com o motivo.
- [x] Testes: 57 do núcleo (números, listas, regras, horário, arquivo, teclado, recentes, contatos) e 54 do app em cada sabor (repositórios, decisão com banco real, respostas, telas, rótulos das abas). Lint sem avisos nos dois sabores.
- [x] Etapa A no emulador: sugestão pelo teclado, ligação para o contato e para número digitado (depuração e publicação/R8), recentes com a ligação feita e o bloqueio com motivo, contatos.
- [ ] Testado no Galaxy S22 e no Redmi Note 12S com ligações reais entre os dois (os dois chips), inclusive ligar pelo Sossego e escolher o chip.
- [ ] Redmi (HyperOS): confirmar que o "Bloquear tudo" recusa com o app fechado (pode exigir Início automático).

## Testes no emulador

- Ligação simulada: `adb emu gsm call <número>` (e `adb emu gsm cancel <número>`).
- Dar o papel de filtro sem a tela do sistema: `adb shell cmd role add-role-holder android.app.role.CALL_SCREENING io.github.brunovinicioslg.sossego`.
- O resultado do filtro aparece no log: `adb logcat | grep SCREENING_COMPLETED` (Allow ou Reject).
- Contato de teste: `content insert` em `raw_contacts` e `data` (nome e `phone_v2`).
