# Alumia — Plano (lanterna)

> ID do pacote: `io.github.brunovinicioslg.alumia` · Na loja: "Alumia – Lanterna no Gesto" · Plano geral: [../PLANO.md](../PLANO.md)

## Objetivo

Ligar e desligar a lanterna **chacoalhando o celular**, mesmo com o app fechado e a tela bloqueada, além de ter atalhos rápidos por botões.

## Status (27/09/2026)

**MVP implementado.** 39 testes automatizados passando (24 da lógica do gesto e 15 do app) e Lint no modo estrito sem avisos.

**Verificado no emulador (Android 16):**
- lanterna pelo botão, pelos atalhos do ícone e pela notificação;
- a luz continua acesa com o app fechado;
- chacoalhar liga e desliga, também com a tela desligada, com a vibração certa para cada caso;
- bloco nas Configurações rápidas;
- o serviço para sozinho quando não é necessário;
- volta sozinho depois de atualização e de reinício do celular;
- se o sistema encerrar o processo, o serviço é reiniciado.

**Falta verificar nos aparelhos reais (Galaxy S22 e Redmi Note 12S):**
- flash físico e níveis de intensidade;
- chacoalhar de verdade em várias situações, com medição de acionamentos falsos;
- sensor de proximidade no bolso;
- consumo de bateria em 24 h;
- sobrevivência no HyperOS e no One UI;
- widget e tecla lateral.

**Google Play (30/09/2026):** versão 1.0.0, pacote `:app:bundleRelease` assinado com a chave de envio, textos, imagens e política de privacidade prontos. Falta o vídeo do serviço em primeiro plano, que a Play exige para o "uso especial" (roteiro em [../PUBLICAR.md](../PUBLICAR.md)).

Comportamento da plataforma a conhecer: se o sistema encerrar o processo à força com a luz acesa, o Android apaga a lanterna. A detecção volta sozinha, mas a luz não é religada automaticamente.

## Viabilidade

- **Android:** total. Para rodar em segundo plano, o app precisa de um serviço em primeiro plano, que sempre publica uma notificação fixa. O app não pode escondê-la sozinho: se o canal for criado sem importância, o Android sobe a notificação de volta. **O usuário pode desligá-la** (permissão de notificações ou o canal "Chacoalhar"), e o serviço continua rodando. No Android 13+ o app aparece só na lista de apps ativos.
- **iOS:** o chacoalhar só funciona com o app aberto, e a Apple recusa novos apps de lanterna sem diferencial forte (regra 4.3(b)). **Recomendação: somente Android.**

## Funcionalidades

### MVP (v1.0)

1. **Gesto configurável:** "chacoalhar" com sensibilidade (baixa, média ou alta) e quantidade de sacudidas (2, 3 ou 4). Sacudidas lentas, caminhada, corrida e quedas são ignoradas.
2. **Funciona em qualquer situação:** app fechado, tela bloqueada e, como opção, com a tela desligada.
3. **Proteção contra acionamento acidental:**
   - sensor de proximidade: no bolso ou na bolsa, ignora o gesto;
   - ignora durante chamadas;
   - intervalo mínimo de 1,5 s entre acionamentos;
   - algoritmo calibrado com dados reais de caminhada, corrida, carro, ônibus, moto e bicicleta.
4. **Retorno tátil:** vibração curta ao ligar e padrão diferente ao desligar.
5. **Desligamento automático:** temporizador (1, 5, 10 ou 30 min, ou nunca) e bateria abaixo de X%.
6. **Serviço em segundo plano:**
   - notificação fixa com botões "Ligar/Desligar" e "Desativar chacoalhar". A chave "Notificação fixa" no app leva à tela do Android que a esconde ou mostra (29/09/2026, a pedido do usuário: a notificação incomodava);
   - também fica ativo enquanto o app mantém a lanterna acesa, porque o Android apaga a luz se o processo que a acendeu for encerrado;
   - inicia sozinho quando o celular liga;
   - volta a funcionar depois de atualizações do app.
7. **Atalhos:**
   | Atalho | Como funciona |
   |---|---|
   | Bloco nas Configurações Rápidas | Liga/desliga a detecção por chacoalhar (a lanterna em si já tem bloco nativo do Android) |
   | Widget na tela inicial | Botão grande de liga/desliga |
   | Atalhos do ícone (pressionar e segurar) | "Ligar/desligar lanterna" e "Chacoalhar liga/desliga" |
   | Botão na notificação | Liga/desliga sem abrir o app |
   | Tecla lateral (Samsung, "pressionar 2x") e toque nas costas (Pixel) | O sistema abre o app; uma tela invisível alterna a lanterna e fecha na hora. Instruções no app. |
   | Segurar volume com a tela desligada | **Experimental.** Só é possível com Serviço de Acessibilidade, que a Play aceita com restrições. Entra numa versão para GitHub/F-Droid e só vai para a Play se passar na revisão. |
8. **Tela principal:** botão grande de liga/desliga, estado da detecção, **intensidade da lanterna** (Android 13+ em aparelhos que suportam) e configurações.
9. **Primeiro uso:** guia para liberar a otimização de bateria, com instruções por marca. A permissão de notificações não é mais cobrada: sem ela o chacoalhar funciona do mesmo jeito, e o app só a pede quando você liga a "Notificação fixa".

### Depois (v1.1+)

- Gesto alternativo: "movimento de corte" duas vezes (estilo Motorola), com acelerômetro + giroscópio.
- Modo SOS em código Morse e estroboscópio (com aviso de fotossensibilidade), também como atalho do ícone.
- Luz de tela (aparelhos sem flash).
- Agendamento: detectar só em certos horários (ex.: 18h às 6h), o que economiza bateria.
- Detectar só com a tela desligada, ou só com ela ligada.
- Botão no relógio (Wear OS).

## Arquitetura técnica

**Módulos:**
- `core`: Kotlin puro, sem Android, 100% testável. Contém:
  - o detector de gesto (`ShakeDetector`);
  - o modelo de configurações com validação;
  - as regras de decisão: quando o serviço roda, bateria baixa, bloqueio no bolso ou em chamada, nível de intensidade.
- `app`: interface Compose, serviço, controle do flash (`torch/TorchController`), widget, bloco rápido e atalhos. Dependências montadas à mão num `AppContainer`, porque o app é pequeno demais para justificar um framework de injeção.

**Serviço:**
- Tipo de serviço em primeiro plano: `specialUse`, com justificativa no manifesto e na Play Console.
- Tem permissão para iniciar no boot mesmo com as restrições do Android 15, que não se aplicam a esse tipo.

**Sensor:**
- Acelerômetro a ~50 Hz com filtro passa-alta (remove a gravidade); o gesto é detectado por contagem de picos alternados dentro de uma janela de tempo.
- Com a tela desligada: usar a variante *wake-up* do acelerômetro quando o aparelho tiver, para não precisar manter a CPU acordada. Sem ela, *wake lock* parcial, com custo de bateria medido.

**Fonte única do estado da lanterna:**
- O `TorchCallback` avisa quando o flash muda por fora (bloco nativo, outro app). App, widget e notificação ficam sempre sincronizados.
- Câmera ocupada por outro app → lanterna indisponível → vibração de erro.

**Configurações:** DataStore.

**Permissões:**
- Usadas: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE` e `WAKE_LOCK` (se necessário).
- **Não pede câmera nem internet.**
- Para a bateria, abre a tela do sistema em vez de usar `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, que é restrita pela Play.

**Casos extremos a cobrir:**
- chamada ativa;
- câmera em uso;
- aparelho sem flash (oferecer luz de tela);
- economia de bateria ligada;
- aparelho superaquecido;
- reinício do celular;
- atualização do app;
- app removido dos recentes;
- "forçar parada" pelo usuário (não há como se recuperar; é o comportamento esperado).

## Prova de conceito (antes do MVP)

1. Medir o consumo de bateria da detecção com a tela desligada no Galaxy S22 e no Redmi Note 12S: acelerômetro wake-up × wake lock.
2. Confirmar no Galaxy S22 (Samsung) e no Redmi Note 12S (Xiaomi/HyperOS, o mais agressivo) que o serviço sobrevive 24 h depois de liberar a bateria.
3. Gravar os dados de sensor que vão formar o conjunto de testes.

## Testes específicos

- **Conjunto de dados de sensores versionado**, gravado com um app interno de gravação:
  - chacoalhadas de várias pessoas;
  - caminhar, correr, carro, ônibus, moto e bicicleta;
  - celular vibrando na mesa;
  - celular caindo.

  O detector roda sobre todos esses dados em cada build, como teste de regressão.
- Bateria: `dumpsys batterystats` / Battery Historian.
- Sobrevivência: reiniciar, atualizar, fechar pelos recentes e deixar em modo soneca (`adb shell dumpsys deviceidle force-idle`).

## Critérios de aceitação

- [ ] Com o app fechado e a tela bloqueada, a lanterna acende em **< 300 ms depois do fim do gesto** em ≥ 95% das tentativas. O detector sozinho já é verificado nos testes: ≤ 150 ms.
- [ ] **Zero acionamentos falsos** em 1 h de caminhada, 1 h de corrida e 1 h de carro/ônibus, com o celular no bolso e na mão.
- [ ] Detecção com a tela desligada consome **≤ 2% de bateria em 24 h** nos aparelhos de referência.
- [ ] O serviço sobrevive a reinício, atualização e remoção dos recentes; em Xiaomi e Samsung, depois de seguir o guia de bateria.
- [ ] Ligar pelo bloco nativo do Android atualiza app, widget e notificação em < 1 s.
- [ ] Nenhum crash ou ANR em 7 dias de uso contínuo nos aparelhos de teste.
