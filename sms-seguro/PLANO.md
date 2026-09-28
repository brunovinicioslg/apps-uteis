# Sigilo — Plano (SMS)

> ID do pacote: `io.github.brunovinicioslg.sigilo` · Na loja: "Sigilo – SMS Criptografado" · Plano geral: [../PLANO.md](../PLANO.md)

## Objetivo

Mensageiro com **visual parecido com o do WhatsApp** que usa **SMS como transporte** e tem **criptografia de ponta a ponta**:
- sem servidor: as conversas ficam só nos aparelhos, criptografadas;
- senha para abrir o app;
- mensagens temporárias;
- bloqueio de números;
- MMS.

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
