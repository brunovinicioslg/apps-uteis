# Ladeira — Plano (GPS)

> ID do pacote: `io.github.brunovinicioslg.ladeira` · Na loja: "Ladeira – GPS Subida e Descida" · Plano geral: [../PLANO.md](../PLANO.md)

## Objetivo

GPS que funciona **sem internet** e mostra a **inclinação das vias**, avisando com antecedência sobre **subidas e descidas**, principalmente as longas. Também avisa sobre radares, buracos, lombadas e outros pontos marcados.

## Status (28/09/2026)

**Primeira versão do app funcionando no emulador** (102 testes automáticos: 57 do núcleo, 7 da ferramenta, 38 do app).

**App Android** (`androidApp/`):
- mapa offline (MapLibre + arquivo PMTiles da região), claro e escuro, em português;
- GPS em segundo plano: serviço de localização em primeiro plano, continua com a tela desligada, notificação com botão Parar;
- reconhece a via, desenha a estrada à frente colorida pela inclinação (verde, amarelo, laranja, vermelho) e gira o mapa no sentido da viagem;
- painel: velocidade, inclinação atual, altitude, próxima subida ou descida, próximo radar com limite, placa de velocidade da via;
- avisos por voz em português, uma vez cada, abaixando a música enquanto fala; alertas que chegam antes da voz estar pronta esperam por ela;
- perfis: carro, caminhão, moto, bicicleta, a pé (sem voz);
- tela Mapas e ajustes: importar regiões (arquivos `.ldrp` e `.pmtiles`), ver tamanho e problemas, apagar, ligar ou desligar a voz, licenças;
- idioma do app escolhido à parte nas configurações do Android 13+.

**Teste no emulador** (trajeto simulado de 8 km pela BR-040 sentido BH, a 72 km/h):
- ficou na pista certa o tempo todo, mesmo com o emulador informando a direção errada (sempre 0°);
- falou: "Descida longa em 200 metros. 1,7 quilômetros com 6 por cento. Use o freio motor." (caminhão) e "Radar em 300 metros. Limite de 110.";
- com a tela desligada, continuou falando os radares;
- o botão Parar da notificação encerra o serviço e desliga o GPS.

**Núcleo** (`shared/`, Kotlin Multiplatform, pronto para o iOS):
- matemática geográfica;
- modelo da malha viária;
- pacote offline compacto por células (o celular lê só a região ao redor), resistente a arquivo corrompido: 2.000 casos de fuzzing, e verificação completa na importação;
- detecção de subidas e descidas por veículo, com suavização do terreno e junção de trechos interrompidos;
- previsão da via à frente sem rota (segue a mesma via ou a continuação mais natural e para em cruzamentos em T);
- map matching (reconhece a via mesmo com uma via marginal a 15 m e ruído de GPS de 8 m, e ignora viadutos que passam por cima);
- motor de avisos que fala uma única vez, com antecedência proporcional à velocidade;
- radares no sentido certo;
- motor de direção (`DriveEngine`): junta tudo a cada posição do GPS, recarrega as vias conforme o veículo anda e procura de novo a cada 300 m quando está fora das regiões baixadas.

**Ferramenta de dados** (`tools/`):
- baixa as vias do OpenStreetMap (Overpass, em pedaços pequenos) e o relevo (AWS Terrain Tiles);
- monta a malha, com pontes e viadutos interpolados em linha reta;
- gera o pacote;
- `track` gera um trajeto simulado pela via, para tocar no GPS do emulador;
- `drive` passa um trajeto pelo mesmo motor do app e mostra o que ele falaria e quando.

Validação com dados reais (sul de BH, 514 km de vias, pacote de 0,2 MB), na BR-040:

| Sentido | Carro | Caminhão |
|---|---|---|
| Sul (subindo, saindo de BH) | subida de 6,6% por 775 m | subida longa: 3,4 km a 5,2% |
| Norte (descendo para BH) | descidas de 6,1% por 1,0 e 1,6 km | descida longa: 1,65 km a 5,8% e 3,2 km a 5,1% |

Inclinações máximas entre 5% e 8,8%, plausíveis para uma rodovia de serra.

**Falta:**
1. Pacote de Minas Gerais inteiro: trocar o Overpass pelo arquivo do OpenStreetMap (Geofabrik) processado localmente; mapa PMTiles de MG.
2. Baixar regiões dentro do app (hoje é por importação de arquivo); hospedar os pacotes no GitHub Releases.
3. Marcar radares e buracos pelo app; gravar trajetos com altitude.
4. Testar dirigindo de verdade (S22 e Redmi): GPS real, tela desligada por muito tempo, Bluetooth do carro.
5. Tamanho: o APK universal tem 51 MB (o MapLibre traz código para 4 tipos de processador); na Play o pacote AAB entrega só o necessário (cerca de 20 MB).
6. Verificar no Android 16 em aparelho real a regra nova de áudio em segundo plano: o emulador registra que a voz "seria silenciada" quando o app está em segundo plano, mas hoje ainda fala.

## Decisões tomadas

- **Rotas em fases:** v1 avisa sobre a via atual; v2 traz rotas offline de A até B.
- **Dados:** ficam no aparelho e vêm do OpenStreetMap; compartilhamento entre amigos por arquivo ou QR. **Sem servidor próprio.** A arquitetura já fica preparada para uma sincronização comunitária no futuro.
- **Hospedagem dos mapas:** apenas arquivos estáticos (GitHub Releases ou Cloudflare R2). Não é servidor, é download de arquivo.

## Funcionalidades

### v1 — avisos na via atual

1. **Mapa** OpenStreetMap vetorial:
   - estilos claro, escuro e noturno (automático);
   - modo "seguir" girando conforme a direção;
   - sombreamento de relevo; 3D opcional.
2. **Mapas offline:**
   - baixar por estado (Brasil primeiro), vendo o tamanho antes;
   - atualização mensal; download retomável; opção "só no Wi-Fi";
   - apagar mapas.
3. **Painel de condução:** velocidade, **limite da via** (do OSM), altitude, **inclinação atual (%)**, direção e precisão do GPS.
4. **Aviso antecipado de subida/descida** (função principal):
   - identifica a via em que o usuário está e **prevê o caminho à frente** (segue a mesma via / via principal) por 2 a 5 km, conforme a velocidade;
   - calcula o perfil de elevação desse trecho;
   - avisa, por exemplo: *"Subida de 7% por 1,2 km em 400 m"* ou *"Descida longa: 5,8 km com média de 6% — use o freio motor"*;
   - visual + **voz em pt-BR** (abaixa a música enquanto fala);
   - **mini-gráfico** do perfil dos próximos quilômetros;
   - **perfis de veículo** com limites próprios:
     | Perfil | Exemplo de aviso padrão |
     |---|---|
     | Caminhão/ônibus | Descidas ≥ 4% por ≥ 1 km |
     | Carro | Subidas/descidas ≥ 6% por ≥ 500 m |
     | Moto | Parecido com carro + curvas em descida |
     | Bicicleta | Subidas ≥ 3% |
     | A pé | Apenas o perfil, sem voz |
5. **Alertas de pontos:**
   - tipos: radar fixo (com velocidade), radar de semáforo, lombada eletrônica, lombada/quebra-molas, buraco, curva perigosa, trecho alagável, pedágio, obra, personalizado;
   - fontes: **OpenStreetMap** (radares e lombadas já mapeados, incluídos no pacote do estado) + **marcações do usuário** (botão grande "marcar aqui" com 1 toque durante a condução; o tipo é escolhido depois, com o carro parado);
   - avisa por distância **e sentido**: um radar do outro lado da pista não alerta;
   - compartilhar: exportar e importar lista (GeoJSON/GPX) ou QR para poucos pontos.
6. **Gravação de trajetos:**
   - GPS + altitude, com histórico e estatísticas (distância, subida e descida acumuladas, velocidades);
   - gráfico de elevação e exportação em GPX;
   - a altitude gravada **corrige localmente o perfil das vias** já percorridas.
7. **Segundo plano:** continua funcionando com a tela desligada, com aviso por voz.
8. **Modo economia de bateria.**

### v2 — rotas offline

- Busca offline de endereços, ruas e locais.
- Rota de A até B com **perfil de elevação completo**, subida total e inclinação máxima.
- Opções **"menos subidas"** e **"evitar descidas íngremes"** (caminhões).
- Navegação curva a curva com voz e recálculo automático.

### v3 — futuro

- Android Auto (Car App Library, categoria navegação).
- iOS (SwiftUI + MapLibre iOS + lógica KMP).
- Sincronização comunitária opcional, se um dia for decidido ter servidor.
- Outros países, respeitando leis locais sobre alerta de radar.

## Arquitetura técnica

### Dados de mapa e relevo (pipeline em `tools/`, roda no GitHub Actions)

```
OpenStreetMap (Geofabrik Brasil)  +  Modelo de terreno (Copernicus DEM 30 m)
             │                                   │
             └──────────────┬────────────────────┘
                            ▼
           Pipeline mensal (Planetiler + scripts)
                            │
      ┌─────────────────────┼─────────────────────┐
      ▼                     ▼                     ▼
 Mapa vetorial         Rede viária             Relevo leve
 (PMTiles/estado)   (vias + perfil de       (sombreamento,
                     elevação + radares       zoom baixo)
                     e lombadas do OSM)
                            │
                            ▼
      Arquivos estáticos por estado (GitHub Releases / Cloudflare R2)
```

- **Mapa vetorial:** PMTiles por estado. O app baixa o arquivo inteiro e abre com o MapLibre (`pmtiles://file://`). Os "pacotes offline" nativos do MapLibre não suportam PMTiles, por isso o download do arquivo inteiro é o caminho certo.
- **Perfil de elevação pré-calculado:**
  - o pipeline amostra o terreno a cada ~25 m ao longo de cada via e grava no pacote do estado;
  - o app **não precisa do modelo de terreno bruto**, que é gigante;
  - **pontes, viadutos e túneis** (marcados no OSM) usam interpolação linear entre as pontas, porque o modelo de terreno mede o chão embaixo e não o tabuleiro. Sem isso haveria alertas falsos.
- **Rede viária:** grafo compacto por estado (vias, ligações, classe, nome, limite de velocidade, ponte/túnel, elevação, pontos do OSM), usado para identificar a via atual, prever o caminho à frente e, na v2, calcular rotas.
- **Motor de rotas (v2):** a prova de conceito compara **Valhalla** (C++, roda em Android e iOS, já entende inclinação), **GraphHopper** e **BRouter** (Java) contra um motor próprio em Kotlin sobre o grafo acima. Como o iOS está no plano, a compatibilidade com ele pesa na escolha.
- **Hospedagem:** GitHub Releases (grátis, até 2 GB por arquivo) ou Cloudflare R2 (sem custo de download, poucos centavos de armazenamento).
- **Licenças:** OpenStreetMap (ODbL: atribuição "© colaboradores do OpenStreetMap" no mapa), Copernicus DEM (atribuição), MapLibre (BSD).

### Altitude e inclinação

- **Três fontes combinadas** (filtro de Kalman) para a altitude e a inclinação atuais:
  | Fonte | Ponto forte | Ponto fraco |
  |---|---|---|
  | Modelo de terreno | Absoluto, disponível para toda a via à frente | Falha em pontes e viadutos (corrigido acima) |
  | Barômetro (parte dos aparelhos) | Variação precisa em tempo real | Deriva com o clima |
  | GPS | Sempre disponível | Ruído vertical de ±10 a 20 m |
- **Previsão da via à frente:** usa sempre o perfil pré-calculado, suavizado.
- **Segmentação em trechos:** limite de inclinação + extensão mínima, juntando pequenas interrupções. Assim, uma descida longa com um trecho plano no meio continua sendo uma descida longa.

### App

- **`shared` (KMP, Kotlin puro, reaproveitado no iOS):**
  - identificação da via (*map matching* por modelo oculto de Markov);
  - previsão do caminho à frente e segmentação de subidas/descidas;
  - alertas de pontos (distância + sentido);
  - fusão de altitude;
  - leitura e escrita de GPX/GeoJSON.
- **`androidApp`:**
  - Compose + MapLibre Native Android;
  - GNSS pelo `LocationManager` (não depende do Google Play Services, então funciona no F-Droid);
  - TextToSpeech com foco de áudio;
  - Room (trajetos, pontos) + arquivos (pacotes de mapa); downloads via WorkManager.
- **Serviço em primeiro plano do tipo `location`**, iniciado com o app aberto. Assim **não precisa** da permissão "localização o tempo todo", que exige revisão extra na Play.
- **Permissões:** localização precisa, `FOREGROUND_SERVICE_LOCATION`, notificações, internet (só para baixar mapas).

## Prova de conceito (antes do MVP)

1. Pipeline de 1 estado (ex.: SP): tamanho final dos pacotes e tempo de geração.
2. Precisão do perfil pré-calculado × trajetos reais gravados com o barômetro do **Galaxy S22**, em serra e em cidade. O **Redmi Note 12S** não tem barômetro, então testa o caminho "só GPS + terreno".
3. *Map matching* em casos difíceis: vias paralelas, viadutos sobrepostos, rotatórias, túneis (sem sinal → estimativa por velocidade).
4. Comparação dos motores de rota para a v2.

## Testes específicos

- **Reprodução de viagens reais:** dezenas de trajetos gravados (serras como Anchieta/Imigrantes, rodovias, cidade), com os alertas esperados marcados à mão. O CI verifica que os alertas certos aparecem, na distância certa.
- Localização simulada no emulador para testes de interface.
- Validação do relevo: comparar com placas de "declive acentuado" e com marcos geodésicos do IBGE.
- Medir falsos alertas e alertas perdidos a cada 100 km.
- Bateria e memória em 4 h de navegação contínua.

## Critérios de aceitação (v1)

- [ ] Aviso de subida/descida relevante com **≥ 300 m de antecedência a 60 km/h** em ≥ 95% dos trechos de referência.
- [ ] **≤ 1 alerta falso a cada 100 km** nas rotas de teste.
- [ ] Funciona 100% em **modo avião** depois de baixar o estado.
- [ ] Radares: aviso no sentido certo em ≥ 99% dos casos de teste; nenhum aviso para radar do sentido oposto.
- [ ] 4 h de navegação sem crash, com memória estável.

## Avisos legais

- Alerta de radar é permitido no Brasil. Em alguns países (ex.: França, Alemanha) é proibido, o que deve ser tratado antes de lançar fora do Brasil.
- O app não substitui a sinalização da via. A interface do modo condução é pensada para uso com um toque, e o app avisa para não manusear o celular dirigindo.
