# Sigilo — Plano (SMS)

> ID do pacote: `io.github.brunovinicioslg.sigilo` · Na loja: "Sigilo – SMS Criptografado" · Plano geral: [../PLANO.md](../PLANO.md)

## Objetivo

Mensageiro com **visual parecido com o do WhatsApp** que usa **SMS como transporte** e tem **criptografia de ponta a ponta**:
- sem servidor: as conversas ficam só nos aparelhos, criptografadas;
- senha para abrir o app;
- mensagens temporárias;
- bloqueio de números;
- MMS.

## Status (28/09/2026)

**Primeira versão do app funcionando no emulador** (53 testes automáticos: 23 do núcleo, 30 do app).

**App Android** (`app/`):
- **app de SMS padrão completo** para o Android: recebe e envia SMS comuns, grava-os no banco de SMS do sistema (como todo app de SMS), importa o histórico existente, confirma entrega (✓ enviada, ✓✓ entregue), "responder com mensagem" ao recusar uma ligação, abrir conversa a partir de links `sms:`;
- **conversas criptografadas**: convite por SMS (17 SMS), aceite com um toque (16 SMS, uma vez), confirmação automática (1 SMS); depois, uma mensagem curta ocupa 1 ou 2 SMS e o app mostra quantos antes de enviar;
- mensagens criptografadas **nunca** vão para o banco de SMS do sistema;
- **senha para abrir** (opcional): Argon2id + chave presa ao chip de segurança (Keystore/StrongBox); espera crescente após 5 senhas erradas; bloqueio automático ao sair do app (na hora, 1, 5 ou 30 min);
- **SMS recebidos com o app bloqueado** esperam numa fila (os criptografados continuam cifrados) e são abertos ao desbloquear; a notificação não mostra nada além de "Novas mensagens";
- **banco criptografado** SQLCipher (AES-256) com mensagens e estado do protocolo Signal; apagar sobrescreve os dados;
- **mensagens temporárias** (30 s a 1 semana), iguais nos dois celulares, somem da tela na hora certa;
- **chave do contato mudou**: mensagens seguradas até o usuário aceitar; número de segurança de 60 dígitos para comparar;
- **bloqueio de números** na lista oficial do Android (vale também para ligações);
- privacidade: sem capturas de tela nem prévia em apps recentes, notificações de conversas criptografadas sem remetente nem texto, nada no backup do Android;
- **dois chips:** cada conversa envia sempre pelo mesmo chip, fixado no primeiro envio (ou no chip em que a conversa chegou). O contato conhece você por esse número, e as respostas automáticas nunca fazem o Android perguntar o chip. Em celular com dois chips, a conversa mostra por qual chip envia e permite trocar, com aviso nas conversas criptografadas;
- **resposta rápida pela notificação** em SMS comuns (nunca com senha nem em conversas criptografadas, cujas notificações não dizem quem é); depois de responder, a notificação mostra "Você: …" e some;
- **teclado sem aprendizado** nas conversas criptografadas (Gboard e teclado Samsung entram no modo anônimo).

**Teste no emulador** (com um "segundo celular" no computador, `peer/`, que injeta SMS no emulador):
- SMS comum recebido, respondido e entregue;
- troca de chaves completa, mensagens criptografadas nos dois sentidos, texto com acentos;
- mensagem temporária de 30 s sumindo nos dois lados;
- mensagem criptografada chegando com o app bloqueado e aberta depois da senha;
- **versão de publicação (R8)**: o teste revelou que o R8 quebrava a libsignal (ela chama o Java por nome, via JNI); corrigido com regras de preservação e testado de novo.

**Falta:**
1. **MMS**: hoje o app só registra que chegou um MMS. Baixar e mostrar imagens, e enviar MMS.
2. Pareamento por **QR code** (sem os 17 SMS do convite) e verificação do número de segurança por QR.
3. Testar a escolha do chip com os dois chips reais (o emulador tem um só; a lógica tem testes automáticos).
4. SMS de dados binários (mensagem curta em 1 SMS sempre).
5. Backup local criptografado para trocar de celular.
6. **Teste entre os dois celulares reais e operadoras** (S22 × Redmi).
7. Auditoria externa.

**Custo em SMS medido** (no emulador e nos testes):

| Tipo | SMS |
|---|---|
| Convite (uma vez por contato) | 17 |
| Aceitar o convite (uma vez) | 16 |
| Confirmação automática (uma vez) | 1 |
| Mensagem curta (até ~20 caracteres) | 1 |
| Mensagem média (76 caracteres) | 2 |

O protocolo ocupa **90 bytes** por mensagem (medido: constante), 37 deles do ratchet pós-quântico; sobram 112 bytes por SMS de texto.

Detalhe técnico registrado: a libsignal usa ordens diferentes de endereços em `SessionBuilder` (contato, local) e `SessionCipher` (local, contato). O código centraliza isso em duas funções.

## Limitações que nenhuma solução contorna

1. **iOS: impossível via SMS.** A Apple não permite que apps enviem SMS automaticamente nem leiam SMS recebidos. No iOS só seria possível uma versão "somente internet" (ver [Internet sem servidor](#internet-sem-servidor-fase-futura)).
2. **Android: precisa ser o app de SMS padrão.** A Play só libera permissões de SMS para o app padrão. Então ele é um **app de SMS completo**: também recebe e envia SMS/MMS comuns, sem criptografia, de qualquer pessoa. Só conversas entre dois usuários do app são criptografadas.
3. **Metadados ficam visíveis.** A operadora sempre vê **quem** falou com **quem**, **quando** e **quantos** SMS. O **conteúdo** fica protegido.
4. **Custo:** uma mensagem criptografada ocupa de 1,5 a 3 vezes mais SMS. O app mostra, por exemplo, *"esta mensagem usará 3 SMS"*. Em planos com SMS ilimitado, comuns no Brasil, não pesa.
5. **Entrega:** SMS não garante ordem nem entrega. O protocolo tolera partes perdidas, fora de ordem ou duplicadas.
6. **MMS:** suporte irregular nas operadoras brasileiras. MMS criptografado (anexo binário) pode ser recusado ou alterado por algumas operadoras, então fica como **experimental** e é testado por operadora.
7. **Aparelho comprometido:** se o celular tiver malware com acesso total (root, spyware), nenhuma criptografia protege. O plano reduz os riscos (bloqueio do app, bloqueio de capturas de tela), mas não resolve esse caso.

## Funcionalidades

### MVP (v1.0)

1. **Visual estilo WhatsApp, sem copiar a marca** (nome, logo e cores exatas são proibidos pela política da Play):
   - lista de conversas com foto e nome do contato;
   - bolhas, horário e **status enviado/entregue** (confirmação de entrega do SMS);
   - **cadeado** nas conversas criptografadas;
   - busca, tema claro/escuro, emojis.
2. **SMS/MMS comum completo** (obrigatório para ser app padrão):
   - enviar e receber SMS longos e MMS com imagem;
   - notificações com resposta rápida;
   - "responder chamada com mensagem";
   - **dual chip** (escolher o chip por conversa);
   - contador de caracteres.
3. **Conversas criptografadas:**
   - **pareamento (troca de chaves):**
     - a) **presencial por QR code** (recomendado): verificação garantida e suporta as chaves pós-quânticas, que são grandes;
     - b) **convite por SMS**: troca automática de ~10 a 20 SMS, uma única vez, e depois comparar o "número de segurança" (60 dígitos ou QR);
   - quando o contato também tem o app, oferece "ativar criptografia";
   - indicadores claros: criptografada × comum, verificada × não verificada;
   - **alerta se a chave do contato mudar.**
4. **Senha para entrar:**
   - PIN ou senha, com biometria opcional;
   - bloqueio automático (imediato, 1 min, 5 min...);
   - espera crescente após erros; opção de apagar tudo após N tentativas.
5. **Mensagens temporárias:**
   - por conversa, de 30 s a 1 semana, contando a partir da leitura ou do envio;
   - a configuração viaja dentro da mensagem criptografada, então vale nos dois aparelhos;
   - apagamento seguro.
6. **Bloqueio de números:**
   - usa a lista oficial do sistema (vale também para chamadas);
   - filtros opcionais: desconhecidos e palavras-chave.
7. **Privacidade:**
   - notificação sem conteúdo ("Nova mensagem") por padrão nas conversas criptografadas;
   - bloqueio de capturas de tela;
   - conteúdo oculto na tela de apps recentes;
   - teclado em modo anônimo (não aprende as palavras digitadas).
8. **Backup local criptografado** (arquivo protegido por senha forte) para trocar de celular. Nada vai para a nuvem.

### Depois (v1.1+)

- MMS criptografado (imagem e áudio comprimidos), experimental.
- Grupos criptografados (um SMS por membro, então custo × N).
- **Modo Cofre (one-time pad)**: ver [Criptografia](#criptografia).
- **Senha de pânico**: abre um perfil vazio ou apaga tudo.
- Transferência presencial por Wi-Fi Direct, Bluetooth ou Wi-Fi local (anexos grandes).
- Internet P2P opcional sem servidor (ver [Internet sem servidor](#internet-sem-servidor-fase-futura)).

## Criptografia

**Princípio:** **nunca inventar criptografia.** Só protocolos padrão, abertos e auditados.

### 1. Protocolo entre aparelhos

- **Signal Protocol:**
  - **PQXDH** (acordo de chaves com componente **pós-quântico**, ML-KEM);
  - **Double Ratchet**: cada mensagem usa uma chave nova. Roubar a chave de hoje não revela as mensagens passadas, e a sessão se recupera sozinha depois de um comprometimento.
- **Implementação:** **libsignal**, a biblioteca oficial do Signal (Rust com bindings Java, AGPL-3.0), auditada e usada por centenas de milhões de pessoas. Em vez de um servidor distribuir as chaves, elas são trocadas direto entre os aparelhos (QR ou SMS). A prova de conceito valida esse uso sem servidor e o tamanho das mensagens.
- **Nível de segurança:** inviável de quebrar com qualquer tecnologia conhecida ou previsível, **inclusive computadores quânticos**. Isso protege contra quem grava SMS hoje para tentar decifrar no futuro.

### 2. Modo Cofre: one-time pad (o único matematicamente inquebrável)

- No **pareamento presencial**, os aparelhos geram e trocam um bloco de bytes aleatórios (ex.: 10 MB, por QR animado, Wi-Fi Direct ou Bluetooth). Metade é usada para A→B e metade para B→A, o que evita reutilização.
- Cada mensagem consome bytes do bloco (XOR) + autenticação de uso único (Poly1305). **Nunca há reutilização**, e os bytes usados são apagados.
- O app mostra a capacidade restante (*"restam ~70 mil mensagens"*). Quando acaba, basta um novo encontro presencial.
- É **matematicamente impossível de quebrar**, desde que o aparelho em si não seja comprometido.

### 3. Formato no SMS

- Pacote binário compacto e versionado: `[versão | tipo | sessão | cabeçalho do ratchet | texto cifrado | autenticação]`.
- Camada própria de fragmentação e remontagem, que tolera ordem trocada, perda e duplicação.
- **Dois modos de envio**, escolhidos automaticamente por contato:
  - a) SMS de **texto** com codificação segura no alfabeto GSM-7 (mais compatível, padrão);
  - b) SMS de **dados binários** (mais eficiente, mas algumas operadoras bloqueiam).
- Compressão de textos curtos antes de cifrar (economiza SMS) + preenchimento (*padding*) para esconder o tamanho exato.
- **Mensagens criptografadas nunca são gravadas no banco de SMS do sistema**, só no banco criptografado do app.

### 4. Armazenamento no aparelho

- Banco **SQLCipher (AES-256)**.
- Chave mestra aleatória, protegida pela senha do usuário (**Argon2id**) **e** por uma chave presa ao hardware (**Android Keystore/StrongBox**).
- Anexos em arquivos cifrados (AES-GCM, Google Tink).
- **Apagar = destruir as chaves** (*crypto-shredding*) + apagamento seguro do SQLite.
- Backup do Android desativado (`allowBackup=false` + regras de extração de dados).

### 5. Modelo de ameaças e auditoria

- `THREAT_MODEL.md` no repositório:
  | Adversário | Situação |
  |---|---|
  | Operadora ou governo interceptando SMS | Conteúdo protegido; metadados expostos |
  | Ladrão com o celular bloqueado | Protegido |
  | Alguém com o celular desbloqueado, mas o app bloqueado | Protegido |
  | Coerção para abrir o app | Senha de pânico (v1.1) |
  | Malware com root | Fora do alcance de qualquer app |
- **Auditoria externa antes de divulgar como seguro.** O Red Team Lab do Open Technology Fund faz auditorias gratuitas para projetos de código aberto de privacidade. O relatório é publicado.

## Internet sem servidor (fase futura)

- **Opção A: Tor onion services** (modelo do app Briar). Cada aparelho tem um endereço `.onion` e as mensagens vão direto de um para o outro pela rede Tor quando os dois estão online, **sem servidor nosso**. Gasta mais bateria. Também permitiria uma **versão iOS** "somente internet".
- **Opção B: pessoas próximas, sem internet**: Wi-Fi local, Wi-Fi Direct ou Bluetooth.
- O protocolo de criptografia é o mesmo; só o transporte muda. Por isso a arquitetura tem uma **interface `Transport`** desde o primeiro dia (SMS / Tor / local).

## Arquitetura técnica

| Módulo | Responsabilidade |
|---|---|
| `crypto` | Protocolo, formato de pacote, fragmentação. Isolado, sem Android na interface, 100% testado, com fuzzing. |
| `transport-sms` | Envio e recebimento (`SmsManager`, `SMS_DELIVER`, `DATA_SMS_RECEIVED`), confirmações de entrega, dual chip |
| `mms` | Montagem e leitura de PDU MMS. Avaliar reaproveitar código do AOSP (Apache 2.0); QKSMS/Quik (GPL) como referência. |
| `data` | Room + SQLCipher, repositórios, migrações testadas |
| `app` | Interface Compose, bloqueio do app, notificações |

**Requisitos de app de SMS padrão:** o Android só lista o app como candidato se ele tiver os quatro:
- receptor `SMS_DELIVER`;
- receptor `WAP_PUSH_DELIVER` (MMS);
- serviço `RESPOND_VIA_MESSAGE`;
- tela de composição para `sms:`/`smsto:`/`mms:`/`mmsto:`.

O pedido para virar padrão usa o `RoleManager` (`ROLE_SMS`). Se o usuário trocar de app padrão, este app para de usar as permissões na hora e mostra a tela "defina como padrão".

## Prova de conceito (antes do MVP)

1. libsignal sem servidor: troca de chaves por QR e por SMS; tamanho real da troca inicial e de cada mensagem.
2. Entrega entre as operadoras dos 2 chips disponíveis (um chip no Galaxy S22 e outro no Redmi Note 12S): SMS de texto × SMS de dados, ordem e perda. A terceira operadora fica para os testadores do beta.
3. MMS com anexo binário em cada operadora.
4. Tamanho do APK e compatibilidade com 16 KB (libsignal + SQLCipher).

## Testes específicos

- **Criptografia:**
  - vetores de teste oficiais;
  - testes de propriedade: cifrar e decifrar devolve o original; **qualquer bit alterado é rejeitado**;
  - **fuzzing** do leitor de pacotes e do remontador de fragmentos.
- **Simulador de rede SMS:** perda, atraso, duplicação e ordem trocada, com milhares de mensagens por execução.
- **SMS entre emuladores** no CI (portas 5554 ↔ 5556).
- **Aparelhos reais** com Vivo, Claro e TIM: matriz de texto × binário × MMS e dual chip.
- **Migração de banco:** atualizar o app **nunca** perde mensagens (testes automáticos de migração do Room).
- **Varredura forense** num aparelho de teste com root: procurar texto claro de conversas criptografadas em logs, no banco do sistema, em notificações, em arquivos temporários e em backups.

## Critérios de aceitação

- [ ] Mensagens criptografadas entre operadoras diferentes entregues e decifradas corretamente em **≥ 99,9%** de 1.000 mensagens de teste.
- [ ] Perder, duplicar ou reordenar qualquer fragmento **não quebra a sessão**.
- [ ] **Nenhum texto claro** de conversa criptografada fora do banco cifrado (verificado pela varredura forense).
- [ ] Troca de chave do contato sempre detectada e exibida.
- [ ] Mensagens temporárias apagadas nos dois aparelhos dentro de ±1 min do prazo (ou na próxima abertura, se o app estiver fechado).
- [ ] SMS/MMS comuns funcionam igual a um app de SMS padrão do mercado: recebimento, notificação, envio, dual chip, bloqueio.
- [ ] Auditoria externa concluída, sem falhas críticas em aberto, antes da divulgação ampla.
