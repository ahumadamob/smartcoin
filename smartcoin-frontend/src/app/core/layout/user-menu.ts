import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ConnectedPosition } from '@angular/cdk/overlay';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ButtonDirective } from 'primeng/button';
import { AuthService } from '../auth/auth.service';

/** Texto del disparador mientras no se conoce el email. */
const FALLBACK_LABEL = 'Mi cuenta';

/**
 * Menú del usuario (HU-05), a la derecha de la barra superior: muestra su email y ofrece cambiar la contraseña y
 * cerrar la sesión. Al cambiar la contraseña se le pasa a la pantalla la ruta actual (`returnUrl`) para que
 * "Cancelar" vuelva a donde estaba.
 *
 * El menú es el de Angular CDK con el aspecto del tema de PrimeNG (T-19), y no `p-menu`: así se conserva el manejo
 * de teclado que tenía con Material. Al abrirlo el foco pasa a la primera opción, las flechas recorren, Enter elige
 * y Escape cierra devolviendo el foco al botón. `p-menu` deja el foco en la lista (`aria-activedescendant`) y no
 * marca ninguna opción al abrir.
 */
@Component({
  selector: 'app-user-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonDirective, CdkMenu, CdkMenuItem, CdkMenuTrigger],
  templateUrl: './user-menu.html',
  styleUrl: './user-menu.scss',
})
export class UserMenu {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly email = this.auth.email;
  protected readonly fallbackLabel = FALLBACK_LABEL;
  /** Debajo del botón, alineado a su borde derecho (está en la esquina de la barra superior). */
  protected readonly positions: ConnectedPosition[] = [
    { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top', offsetY: 4 },
  ];

  protected changePassword(): void {
    void this.router.navigate(['/cambiar-contrasena'], { state: { returnUrl: this.router.url } });
  }

  protected logout(): void {
    this.auth.logout();
  }
}
