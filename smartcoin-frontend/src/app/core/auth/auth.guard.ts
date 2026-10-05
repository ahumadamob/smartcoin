import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Sin token, solo se puede entrar a `/login`. Con el cambio de contraseña pendiente, solo a `/cambiar-contrasena`
 * (HU-04).
 */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }
  return !auth.mustChangePassword() || router.createUrlTree(['/cambiar-contrasena']);
};

/**
 * Para la ruta del cambio de contraseña obligatorio (HU-04): coincide solo mientras hay sesión con el cambio
 * pendiente. Con el cambio ya hecho, la misma URL cae en la ruta del layout (HU-05); sin sesión, el `authGuard` de
 * esa ruta lleva al login.
 */
export const mandatoryPasswordChange: CanMatchFn = () => inject(AuthService).mustChangePassword();

/**
 * Con la sesión iniciada, `/login` no tiene sentido: lleva al presupuesto del mes actual, o al cambio de
 * contraseña si está pendiente.
 */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  if (!auth.isAuthenticated()) {
    return true;
  }
  return inject(Router).createUrlTree([
    auth.mustChangePassword() ? '/cambiar-contrasena' : '/presupuesto',
  ]);
};
