// Build deterministic raster variants from the same Android vector geometry.
// Requires sharp; run with NODE_PATH pointing to an installation containing sharp.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const res = path.join(root, 'app/src/main/res');
const mark = fs.readFileSync(path.join(res, 'drawable/ic_launcher_mark.xml'), 'utf8');
const paths = [...mark.matchAll(/<path\s+([\s\S]*?)\/>/g)].map(([, attrs]) => {
  const attr = name => attrs.match(new RegExp(`android:${name}="([^"]+)"`))?.[1];
  return `<path d="${attr('pathData')}" fill-rule="${attr('fillType') === 'evenOdd' ? 'evenodd' : 'nonzero'}" fill="${attr('fillColor') === '@android:color/transparent' ? 'none' : attr('fillColor')}"${attr('strokeColor') ? ` stroke="${attr('strokeColor')}" stroke-width="${attr('strokeWidth')}" stroke-linecap="round" stroke-linejoin="round"` : ''}/>`;
}).join('\n');
const stops = [...fs.readFileSync(path.join(res, 'drawable/ic_launcher_background.xml'), 'utf8').matchAll(/<item android:offset="([^"]+)" android:color="([^"]+)"/g)]
  .map(([, offset, color]) => `<stop offset="${offset}" stop-color="${color}"/>`).join('');
const svg = round => `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72"><defs><linearGradient id="blue" gradientUnits="userSpaceOnUse" x1="18" y1="18" x2="90" y2="90">${stops}</linearGradient></defs>${round ? '<circle cx="54" cy="54" r="36" fill="url(#blue)"/>' : '<rect x="18" y="18" width="72" height="72" rx="16" fill="url(#blue)"/>'}${paths}</svg>`;
(async () => {
  fs.writeFileSync(path.join(root, 'design/app-icon/splate-launcher.svg'), svg(false));
  fs.writeFileSync(path.join(root, 'design/app-icon/splate-launcher-round.svg'), svg(true));
  for(const [density, size] of [['mdpi',48],['hdpi',72],['xhdpi',96],['xxhdpi',144],['xxxhdpi',192]]) {
    for(const round of [false,true]) await sharp(Buffer.from(svg(round))).resize(size,size).webp({lossless:true}).toFile(path.join(res,`mipmap-${density}`,`ic_launcher${round ? '_round' : ''}.webp`));
  }
  await sharp(Buffer.from(svg(true))).png().toFile(path.join(root,'design/app-icon/splate-launcher-preview.png'));
  await sharp(Buffer.from(svg(false))).png().toFile(path.join(root,'app/src/main/ic_launcher-playstore.png'));
  console.log('Updated 10 launcher WebP variants, SVG masters, README preview and distribution icon.');
})().catch(error => { console.error(error); process.exitCode=1; });
