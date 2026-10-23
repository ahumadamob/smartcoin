// Escribe la clave de licencia de PrimeUI (T-19) en un archivo ignorado por git, para que la aplicación la pase a
// `providePrimeNG`. La clave se lee de la variable de entorno PRIMEUI_LICENSE_KEY y nunca se versiona.
// Corre antes de `npm start`, `npm run build` y `npm test`. Sin la variable, la clave queda vacía: la aplicación
// funciona, pero PrimeNG muestra el aviso de licencia inválida.
import { readFileSync, writeFileSync } from 'node:fs';

const target = new URL('../src/app/core/primeui-license.generated.ts', import.meta.url);
const key = (process.env.PRIMEUI_LICENSE_KEY ?? '').trim();

if (key === '') {
  console.warn('[primeui-license] Falta PRIMEUI_LICENSE_KEY: PrimeNG va a mostrar el aviso de licencia.');
}

const content =
  '// Generado por scripts/primeui-license.mjs desde PRIMEUI_LICENSE_KEY. No editar ni versionar.\n' +
  `export const PRIMEUI_LICENSE = ${JSON.stringify(key)};\n`;

let current = '';
try {
  current = readFileSync(target, 'utf8');
} catch {
  // Todavía no existe.
}
// Solo se escribe si cambió, para no disparar una recompilación de `ng serve` por nada.
if (current !== content) {
  writeFileSync(target, content);
}
