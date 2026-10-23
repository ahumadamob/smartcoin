import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { ButtonDirective } from 'primeng/button';
import { MenuModule } from 'primeng/menu';
import { AuthService } from '../auth/auth.service';

/** Texto del disparador mientras no se conoce el email. */
const FALLBACK_LABEL = 'Mi cuenta';

/**
 * Menú del usuario (HU-05), a la derecha de la barra superior: muestra su email y ofrece cambiar la contraseña y
 * cerrar la sesión. Al cambiar la contraseña se le pasa a la pantalla la ruta actual (`returnUrl`) para que
 * "Cancelar" vuelva a donde estaba. Es un menú emergente de PrimeNG: se recorre con las flechas, Enter elige y
 * Escape lo cierra devolviendo el foco al botón.
 */
@Component({
  selector: 'app-user-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonDirective, MenuModule],
  templateUrl: './user-menu.html',
  styleUrl: './user-menu.scss',
})
export class UserMenu {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly email = this.auth.email;
  protected readonly fallbackLabel = FALLBACK_LABEL;
  protected readonly open = signal(false);
  protected readonly items: MenuItem[] = [
    { label: 'Cambiar contraseña', icon: 'pi pi-key', command: () => this.changePassword() },
    { label: 'Cerrar sesión', icon: 'pi pi-sign-out', command: () => this.logout() },
  ];

  private changePassword(): void {
    void this.router.navigate(['/cambiar-contrasena'], { state: { returnUrl: this.router.url } });
  }

  private logout(): void {
    this.auth.logout();
  }
}
