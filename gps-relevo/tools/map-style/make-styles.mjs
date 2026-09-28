// Generates the offline MapLibre styles for Ladeira from the official Protomaps basemap layers.
// Usage: npm install && node make-styles.mjs ../../androidApp/src/main/assets
// Fonts (Noto Sans, OFL) and sprites (derived from Mapzen icons, MIT) come from
// https://github.com/protomaps/basemaps-assets: fonts/<name>/{0-255,256-511,8192-8447}.pbf and sprites/v4/{light,dark}{,@2x}.{json,png}.
import { layers, namedFlavor } from "@protomaps/basemaps";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const out = process.argv[2];
mkdirSync(join(out, "styles"), { recursive: true });

for (const flavor of ["light", "dark"]) {
  const style = {
    version: 8,
    name: `Ladeira ${flavor}`,
    // Fonts and icons ship inside the app: the map works with no connection at all.
    glyphs: "asset://fonts/{fontstack}/{range}.pbf",
    sprite: `asset://sprites/${flavor}`,
    sources: {
      protomaps: {
        type: "vector",
        // Replaced at runtime with pmtiles://file://<path of the downloaded region>.
        url: "__PMTILES_URL__",
        attribution: "© OpenStreetMap · Protomaps",
      },
    },
    layers: layers("protomaps", namedFlavor(flavor), { lang: "pt" }),
  };
  // Font names without spaces: they become folder names under asset://, where a space would
  // have to survive URL encoding. The fonts folder uses the same names.
  const json = JSON.stringify(style)
    .replaceAll("Noto Sans Regular", "NotoSansRegular")
    .replaceAll("Noto Sans Medium", "NotoSansMedium")
    .replaceAll("Noto Sans Italic", "NotoSansItalic");
  writeFileSync(join(out, "styles", `${flavor}.json`), json);
  const fonts = new Set();
  for (const l of style.layers) for (const f of l.layout?.["text-font"] ?? []) fonts.add(f);
  console.log(`${flavor}: ${style.layers.length} layers, fonts: ${[...fonts].join(" | ")}`);
}
