# Mede Aí — Plano (trena)

> ID do pacote: `io.github.brunovinicioslg.medeai` · Na loja: "Mede Aí – Trena pela Câmera" · Plano geral: [../PLANO.md](../PLANO.md)

## Objetivo

Medir **distâncias, alturas e áreas** apontando a câmera e marcando pontos na tela.

## Status (28/09/2026)

**Pronto e verificado no emulador (Android 16):**
- **Lógica compartilhada** (`shared/`, Kotlin Multiplatform, pronta para o iOS), com 40 testes e milhares de casos aleatórios:
  - distância, trechos, área de polígonos em qualquer posição no espaço (com aviso de contorno torto ou cruzado);
  - retângulo por 3 pontos, altura e ângulos;
  - unidades métricas e imperiais com vírgula em português;
  - medida por inclinação;
  - correção de perspectiva para foto (homografia);
  - nível de bolha.
- **App Android**, com 17 testes de tela e de configurações:
  - **Nível:** a bolha vai para o lado mais alto e troca sozinha entre modo superfície e modo borda;
  - **Medir por inclinação:** câmera com mira; mediu 2,43 m (esperado 2,42 m) e 100,0 cm (esperado 100,0 cm) com o sensor simulado;
  - **Medir por foto:** com uma foto de teste contendo um cartão e uma linha de 10 cm, mediu **10,0 cm**; arrastar um ponto até a metade deu **5,0 cm**, com lupa durante o arraste.
- Um bug de uso encontrado e corrigido no teste: a foto "pulava" quando a instrução mudava de tamanho.

**Modo AR (ARCore), pronto para testar no Galaxy S22:**
- mira central: o ponto vai para o plano detectado sob a mira, senão para a profundidade (no S22), senão para um ponto de textura;
- modos Distância, Trechos e Área (fecha o contorno ao mirar no primeiro ponto), com a medida ao vivo até a mira, desfazer e limpar;
- os pontos são âncoras do ARCore: a medida se corrige sozinha enquanto o celular aprende o ambiente;
- avisos de pouca luz, movimento rápido, falta de textura e distância acima de 5 m;
- em celular sem ARCore (Redmi Note 12S) ou com falha interna do ARCore, a tela explica e indica foto ou inclinação, sem fechar o app;
- o emulador do Android não roda o ARCore (ele expõe as câmeras com números que o ARCore não reconhece), então só o caminho de erro foi visto rodando; a lógica das medidas tem testes automáticos.

**Falta:**
- testar o modo AR no Galaxy S22 com uma trena de verdade (distância, área de uma mesa, parede);
- precisão real dos modos alternativos no Redmi Note 12S;
- histórico e compartilhamento de medidas.

Limitações conhecidas:
- no modo foto, a referência precisa estar no mesmo plano do que se mede, e a foto deve ser tirada de frente (inclinação de até ~30°);
- se o sistema encerrar o app, a foto é reaberta, mas os pontos marcados se perdem.

## Viabilidade e precisão (expectativa honesta)

- **Android:** ARCore ("Google Play Services para RA"), que só funciona em aparelhos certificados. Muitos celulares baratos não têm → **modos alternativos** (abaixo).
- **iOS (futuro):** ARKit em todos os iPhones recentes; o LiDAR dos modelos Pro melhora muito a precisão. Precisa se diferenciar do app Medida da Apple (área, histórico, exportação, planta).
- **Precisão típica de AR:**
  - erro de 1% a 3% com boa luz e superfícies com textura;
  - piora em paredes brancas lisas, vidro, espelho, pouca luz e acima de ~5 m;
  - aparelhos com sensor de profundidade (ToF) se saem melhor.

  O app mostra um indicador de qualidade e avisa que **não substitui uma trena física em medições críticas**.

## Funcionalidades

### MVP (v1.0)

1. **Detecção do ambiente:** planos horizontais e verticais, mais a API de profundidade (quando suportada) para marcar pontos em qualquer superfície.
2. **Mira central:** o ponto é marcado onde está a mira, com o botão "+". É mais preciso que tocar com o dedo, que cobre o alvo. Tem **ímã** (encaixe) em pontos já marcados e em bordas.
3. **Modos:**
   | Modo | Como funciona |
   |---|---|
   | Distância | 2 pontos, com a medida em tempo real enquanto move |
   | Trechos | Vários segmentos: cada trecho e o total |
   | Área | Polígono fechado: área + perímetro. Atalho de retângulo com 3 pontos. |
   | Altura | Ponto no chão + guia vertical até o topo |
   | Nível / inclinômetro | Só com sensores, funciona em qualquer aparelho |
4. **Unidades:** mm, cm e m; polegadas, pés e "pés + polegadas". Casas decimais configuráveis.
5. Desfazer, refazer e limpar.
6. **Salvar e compartilhar:** foto com as medidas desenhadas + dados, histórico local e compartilhamento da imagem.
7. **Indicadores de qualidade:** estado do rastreamento ("mova o celular devagar"), aviso de pouca luz, distância até o ponto e confiança da profundidade.
8. **Primeiro uso:** animação ensinando a "escanear" o ambiente.

### Modos alternativos (aparelhos sem ARCore, e também como extras)

- **Trena por inclinação:**
  - o usuário informa a altura em que segura o celular (ex.: 1,40 m, com calibração);
  - aponta para a base do objeto no chão → distância = altura × tan(ângulo);
  - depois aponta para o topo → altura do objeto.

  Menos precisa, com aviso claro.
- **Medida por foto com referência:**
  - tira-se uma foto com um objeto de tamanho conhecido no mesmo plano (cartão 85,60 × 53,98 mm, folha A4);
  - o usuário marca os 4 cantos da referência → correção de perspectiva (homografia);
  - mede qualquer coisa nesse plano.

### Depois (v1.1+)

- Volume de caixa.
- Medida de ângulo.
- **Planta baixa simples:** medir o cômodo, gerar a planta com a área e exportar em PDF.
- Exportar relatório CSV/PDF.
- Versão iOS (ARKit + LiDAR).

## Arquitetura técnica

**`shared` (KMP):** geometria em Kotlin puro, reaproveitada no iOS:
- distância 3D;
- ajuste de plano por mínimos quadrados;
- projeção do polígono no plano + área pela fórmula do laço (*shoelace*);
- homografia;
- conversão e formatação de unidades.

**`androidApp`:**
- ARCore SDK.
- Renderização leve em OpenGL ES, baseada nos exemplos oficiais do ARCore: fundo da câmera, pontos e linhas.
- **Rótulos com as medidas desenhados em Compose por cima**: os pontos 3D são projetados na tela a cada quadro, o que deixa o texto nítido e simples.
- A prova de conceito compara essa abordagem com a biblioteca SceneView.

**Âncoras:** cada ponto vira uma âncora do ARCore, então continua fixo enquanto o ARCore refina o mapa. O "Instant Placement" fica **desativado** para medir, porque a distância inicial dele é aproximada.

**Manifesto:**
- ARCore declarado como **opcional**: o app instala em todos os aparelhos, e em tempo de execução `ArCoreApk.checkAvailability` decide entre AR e os modos alternativos.
- Permissão de câmera. **Sem internet.**

**Armazenamento:** Room (histórico); imagens na pasta privada do app; compartilhamento via FileProvider.

**Ciclo de vida:** pausar e retomar a sessão de AR (rotação, segundo plano, chamada recebida) é uma fonte clássica de bugs e tem testes dedicados.

## Prova de conceito (antes do MVP)

1. Precisão real no **Galaxy S22** (ARCore + API de profundidade) usando o protocolo de medição abaixo. Os modos alternativos são testados no **Redmi Note 12S**, que não tem ARCore e por isso é o aparelho ideal para esse caminho.
2. OpenGL próprio × SceneView: estabilidade, tamanho do APK, compatibilidade com 16 KB.
3. Gravar sessões com a API de gravação do ARCore para montar o conjunto de testes.

## Testes específicos

- **Protocolo de precisão:** 10 objetos de referência (0,3 m a 5 m) medidos com trena física, 5 repetições cada, em luz boa e ruim, em superfícies com e sem textura. O resultado vira tabela publicada no repositório.
- **Regressão de AR:** sessões gravadas (ARCore Recording & Playback) com medidas conhecidas, reproduzidas automaticamente em aparelho físico.
- **Geometria:** testes de propriedade (ex.: a área de qualquer polígono é invariante a rotação e translação; um retângulo dá largura × altura).
- **Memória e estabilidade:** LeakCanary nas builds de depuração; sessão de 30 min contínua.

## Critérios de aceitação

- [ ] Em aparelho ARCore com boa luz, objetos de 0,3 m a 3 m medidos com erro **≤ 2% ou ≤ 1 cm** (o que for maior) em ≥ 90% das medições.
- [ ] Retângulo de 2 × 3 m no chão: área com erro ≤ 3%.
- [ ] 30 min de sessão AR sem crash, sem ANR e sem vazamento de memória.
- [ ] Girar a tela, ir para segundo plano e voltar, ou receber uma chamada: a sessão retoma sem travar.
- [ ] Em aparelho sem ARCore: o app instala, explica a situação e os modos alternativos funcionam.
