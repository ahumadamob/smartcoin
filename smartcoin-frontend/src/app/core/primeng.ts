import { EnvironmentProviders } from '@angular/core';
import Aura from '@primeuix/themes/aura';
import { definePreset } from '@primeuix/themes';
import { providePrimeNG } from 'primeng/config';
import { PRIMENG_ES } from './primeng-es';
import { PRIMEUI_LICENSE } from './primeui-license.generated';

/**
 * Aura con las medidas de la demo de Sakai. El Aura de `@primeuix/themes` 3.1.0 trae campos y botones más compactos
 * (relleno de 0,625 × 0,375 rem y letra de 0,875 rem) que el que usa la demo (0,75 × 0,5 rem y letra de 1 rem): se
 * vuelven a poner esos valores, y los de las etiquetas y las opciones de las listas, para que se vea igual.
 */
const Sakai = definePreset(Aura, {
  semantic: {
    // La demo no fija letra ni interlineado en los componentes: heredan los de la página (14 px y 1,2).
    typography: { fontSize: '1rem', lineHeight: '1.2' },
    formField: {
      paddingX: '0.75rem',
      paddingY: '0.5rem',
      sm: { fontSize: '0.875rem', paddingX: '0.625rem', paddingY: '0.375rem' },
      lg: { fontSize: '1.125rem', paddingX: '0.875rem', paddingY: '0.625rem' },
    },
    list: { option: { padding: '0.5rem 0.75rem' } },
  },
  components: {
    tag: { root: { fontSize: '0.875rem', fontWeight: '700', padding: '0.25rem 0.5rem' } },
    button: { root: { iconOnlyWidth: '2.5rem', sm: { iconOnlyWidth: '2rem' } } },
  },
});

/**
 * PrimeNG (T-19): preset `Sakai` (Aura con las medidas de la demo: esquinas de 6 px en botones, campos y tablas, 12 px
 * en diálogos y tarjetas, foco de 1 px) con su primario esmeralda, solo en modo claro y con los textos en español.
 *
 * Los estilos van en la capa CSS `primeng`: los que no están en ninguna capa (los de Angular Material, mientras
 * convivan, y los de cada componente) le ganan sin pelear por especificidad. La clave de licencia la genera
 * `scripts/primeui-license.mjs` desde la variable de entorno PRIMEUI_LICENSE_KEY.
 */
export function provideAppPrimeNG(): EnvironmentProviders {
  return providePrimeNG({
    theme: {
      preset: Sakai,
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
