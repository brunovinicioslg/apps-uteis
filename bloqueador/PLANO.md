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

### Fase 2 (planejada): modo Telefone

O mesmo app como **app Telefone padrão**, opcional: o filtro passa a receber todas as ligações (contatos também), o "Bloquear tudo" deixa de tocar por um instante, e o histórico mostra recebidas, perdidas, recusadas, bloqueadas e feitas. Exige teclado, tela de chamada (recebendo, em andamento, espera, conferência, Bluetooth, sensor de proximidade), contatos e caixa postal. Não substitui a gravação de chamadas do Telefone da Samsung (apps de terceiros não gravam desde o Android 10).

## Critérios de aceitação

- [x] Uma ligação da lista negra é recusada sem tocar, qualquer que seja o formato do número (verificado no emulador: +55, com e sem DDD).
- [x] Telemarketing 0303 recusado por padrão (emulador, depuração e publicação/R8).
- [x] Número comum toca normalmente no modo lista negra (emulador).
- [x] Só lista branca: desconhecido recusado, contato toca, quem insiste passa (emulador).
- [x] Bloquear tudo: desconhecido recusado pelo filtro, contato recusado ao tocar; voltando à lista negra, o contato toca de novo (emulador).
- [x] Chamadas liberadas não vão para o histórico; as bloqueadas aparecem com o motivo.
- [x] Testes: 40 do núcleo (números, listas, regras, horário, arquivo) e 41 do app (repositórios, decisão com banco real, respostas, telas). Lint sem avisos.
- [ ] Testado no Galaxy S22 e no Redmi Note 12S com ligações reais entre os dois (os dois chips).
- [ ] Redmi (HyperOS): confirmar que o "Bloquear tudo" recusa com o app fechado (pode exigir Início automático).

## Testes no emulador

- Ligação simulada: `adb emu gsm call <número>` (e `adb emu gsm cancel <número>`).
- Dar o papel de filtro sem a tela do sistema: `adb shell cmd role add-role-holder android.app.role.CALL_SCREENING io.github.brunovinicioslg.sossego`.
- O resultado do filtro aparece no log: `adb logcat | grep SCREENING_COMPLETED` (Allow ou Reject).
- Contato de teste: `content insert` em `raw_contacts` e `data` (nome e `phone_v2`).
