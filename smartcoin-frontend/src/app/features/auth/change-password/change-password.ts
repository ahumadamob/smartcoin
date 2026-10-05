import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ChangePasswordForm } from './change-password-form';

/** Adónde se va al terminar un cambio obligatorio, o al terminar o cancelar uno voluntario sin origen conocido. */
const DEFAULT_RETURN_URL = '/presupuesto';
const CHANGE_PASSWORD_URL = '/cambiar-contrasena';

/**
 * Pantalla `/cambiar-contrasena`. El modo lo fija la ruta (`data.mandatory`):
 * - Obligatorio (HU-04): fuera del layout. Es la única accesible mientras el cambio está pendiente y la única
 *   salida, además de cambiarla, es cerrar la sesión.
 * - Voluntario (HU-05): dentro del layout, con el menú de usuario. Se puede cancelar y volver a donde se estaba
 *   (`returnUrl`, que el menú pasa en el estado de la navegación).
 * En los dos modos el formulario es el mismo y, al cambiarla, se muestra una confirmación.
 */
@Component({
  selector: 'app-change-password',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatCardModule, ChangePasswordForm],
  templateUrl: './change-password.html',
  styleUrl: './change-password.scss',
})
export class ChangePassword {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly mandatory = inject(ActivatedRoute).snapshot.data['mandatory'] === true;
  private readonly returnUrl = safeReturnUrl(inject(Location).getState());

  protected onChanged(): void {
    this.snackBar.open('Contraseña cambiada.', 'Cerrar', { duration: 6000 });
    void this.router.navigateByUrl(this.mandatory ? DEFAULT_RETURN_URL : this.returnUrl);
  }

  protected cancel(): void {
    void this.router.navigateByUrl(this.returnUrl);
  }

  protected logout(): void {
    this.auth.logout();
  }
}

/**
 * Ruta a la que volver, tomada del estado de la navegación. Solo se aceptan rutas internas distintas de esta misma
 * pantalla; cualquier otra cosa (sin estado, tras una recarga sin él, un valor raro) vuelve al presupuesto.
 */
function safeReturnUrl(state: unknown): string {
  const value = (state as { returnUrl?: unknown } | null)?.returnUrl;
  if (
    typeof value !== 'string' ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    value.startsWith('/\\') ||
    value.split(/[?#]/)[0] === CHANGE_PASSWORD_URL
  ) {
    return DEFAULT_RETURN_URL;
  }
  return value;
}
