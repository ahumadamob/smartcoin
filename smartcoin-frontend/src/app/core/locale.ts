import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, Provider } from '@angular/core';

export const APP_LOCALE = 'es-AR';

/** Formato de fecha de negocio en pantalla: dd/MM/yyyy. */
export const DATE_FORMAT = 'dd/MM/yyyy';

/** Registra los datos del locale es-AR y lo deja como locale de la aplicación. */
export function provideLocale(): Provider {
  registerLocaleData(localeEsAr, APP_LOCALE);
  return { provide: LOCALE_ID, useValue: APP_LOCALE };
}
