import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Sin token, solo se puede entrar a `/login`. */
export const authGuard: CanActivateFn = () => {
  return inject(AuthService).isAuthenticated() || inject(Router).createUrlTree(['/login']);
};

/** Con la sesión iniciada, `/login` no tiene sentido: lleva al presupuesto del mes actual. */
export const guestGuard: CanActivateFn = () => {
  return !inject(AuthService).isAuthenticated() || inject(Router).createUrlTree(['/presupuesto']);
};
