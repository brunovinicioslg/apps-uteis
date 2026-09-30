# Mede Aí – Trena pela Câmera

Mede distâncias, alturas e áreas com o celular: pela câmera com realidade aumentada (ARCore), por inclinação ou por uma foto com um objeto de referência. Traz também um nível de bolha. Grátis, sem anúncios e sem coleta de dados.

> Em desenvolvimento: o modo de realidade aumentada ainda está sendo testado em aparelho. Status em [PLANO.md](PLANO.md).

## O que faz

- **Medir com a câmera (AR):** marque pontos na tela para distância, trechos e área; a medida se corrige enquanto o celular reconhece o ambiente. Precisa de um aparelho com ARCore.
- **Medir por inclinação:** aponte para a base e para o topo; o app calcula pela altura do celular e pelo ângulo.
- **Medir por foto:** com um objeto de tamanho conhecido na foto (um cartão, por exemplo), meça o resto no mesmo plano, com correção de perspectiva.
- **Nível:** bolha para superfícies e para bordas.
- Unidades métricas e imperiais.

## Código

- `shared/`: Kotlin Multiplatform: geometria, unidades, área de polígonos, perspectiva e nível, com testes.
- `androidApp/`: o app Android (Jetpack Compose, CameraX, ARCore).

Com o JDK do Android Studio (JBR), dentro desta pasta:

```sh
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
```

## Licença

[GPL-3.0](../LICENSE).

## In English

Mede Aí ("measure it") measures distances, heights and areas with your phone: augmented reality (ARCore), tilt, or a photo with a reference object, plus a bubble level. Free, no ads, no data collected. Still in development.
