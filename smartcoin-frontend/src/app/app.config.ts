import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, TitleStrategy } from '@angular/router';
import { provideApi } from './api';
import { authInterceptor } from './core/auth/auth.interceptor';
import { provideLocale } from './core/locale';
import { PageTitleStrategy } from './core/page-title.strategy';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    { provide: TitleStrategy, useExisting: PageTitleStrategy },
    provideHttpClient(withInterceptors([authInterceptor])),
    // El proxy de `npm start` reenvía /api al backend.
    provideApi(''),
    provideLocale(),
  ],
};
