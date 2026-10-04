import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const LOGIN_URL = '/api/auth/login';
const PASSWORD_CHANGE_REQUIRED = 'PASSWORD_CHANGE_REQUIRED';

/**
 * Agrega `Authorization: Bearer` a cada pedido y, ante un 401 (token vencido, inválido o revocado), descarta el
 * token y va al login. Ante un 403 `PASSWORD_CHANGE_REQUIRED` (RN-50), va al cambio de contraseña. El login queda afuera: ahí un 401 es "credenciales incorrectas" y lo muestra la pantalla.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  if (request.url.endsWith(LOGIN_URL)) {
    return next(request);
  }

  const token = auth.token();
  const authorized =
    token === null ? request : request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        auth.logout();
      } else if (isPasswordChangeRequired(error)) {
        auth.requirePasswordChange();
      }
      return throwError(() => error);
    }),
  );
};

function isPasswordChangeRequired(error: unknown): boolean {
  return (
    error instanceof HttpErrorResponse &&
    error.status === 403 &&
    error.error?.code === PASSWORD_CHANGE_REQUIRED
  );
}
