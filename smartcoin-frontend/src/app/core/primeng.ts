import { EnvironmentProviders } from '@angular/core';
import Nora from '@primeuix/themes/nora';
import { providePrimeNG } from 'primeng/config';
import { PRIMENG_ES } from './primeng-es';
import { PRIMEUI_LICENSE } from './primeui-license.generated';

/**
 * PrimeNG (T-19): preset Nora tal cual (esquinas de 2 px en campos, botones, tablas y diálogos, 4 px en tarjetas,
 * foco de 2 px) con su primario esmeralda, solo en modo claro y con los textos en español.
 *
 * Los estilos van en la capa CSS `primeng`: los que no están en ninguna capa (los de Angular Material, mientras
 * convivan, y los de cada componente) le ganan sin pelear por especificidad. La clave de licencia la genera
 * `scripts/primeui-license.mjs` desde la variable de entorno PRIMEUI_LICENSE_KEY.
 */
export function provideAppPrimeNG(): EnvironmentProviders {
  return providePrimeNG({
    theme: {
      preset: Nora,
      options: { darkModeSelector: false, cssLayer: { name: 'primeng', order: 'primeng' } },
    },
    ripple: false,
    // El botón de la flecha de `p-select` se anuncia como «dropdown trigger», en inglés, y repite lo que ya dice el
    // combobox (que es el que recibe el foco): se oculta a los lectores de pantalla.
    pt: { select: { dropdown: { 'aria-hidden': 'true' } } },
    translation: PRIMENG_ES,
    license: PRIMEUI_LICENSE || undefined,
  });
}
