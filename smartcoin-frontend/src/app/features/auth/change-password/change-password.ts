import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ChangePasswordForm } from './change-password-form';

/**
 * Pantalla `/cambiar-contrasena`, fuera del layout. Con el cambio obligatorio pendiente es la única accesible
 * (HU-04); al terminar va al presupuesto del mes actual.
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

  /** Se lee al abrir la pantalla: después del cambio la bandera baja, pero ya se está navegando. */
  protected readonly mandatory = this.auth.mustChangePassword();

  protected onChanged(): void {
    void this.router.navigate(['/presupuesto']);
  }

  protected logout(): void {
    this.auth.logout();
  }
}
