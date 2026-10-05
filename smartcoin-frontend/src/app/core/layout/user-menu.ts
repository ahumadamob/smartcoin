import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';

/** Texto del disparador mientras no se conoce el email. */
const FALLBACK_LABEL = 'Mi cuenta';

/**
 * Menú del usuario (HU-05): muestra su email y ofrece cambiar la contraseña y cerrar la sesión. Al cambiar la
 * contraseña se le pasa a la pantalla la ruta actual (`returnUrl`) para que "Cancelar" vuelva a donde estaba.
 */
@Component({
  selector: 'app-user-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatMenuModule],
  templateUrl: './user-menu.html',
  styleUrl: './user-menu.scss',
})
export class UserMenu {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly email = this.auth.email;
  protected readonly fallbackLabel = FALLBACK_LABEL;

  protected changePassword(): void {
    void this.router.navigate(['/cambiar-contrasena'], { state: { returnUrl: this.router.url } });
  }

  protected logout(): void {
    this.auth.logout();
  }
}
