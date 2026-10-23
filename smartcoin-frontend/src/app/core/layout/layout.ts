import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ToastModule } from 'primeng/toast';
import { AuthService } from '../auth/auth.service';
import { UserMenu } from './user-menu';

interface NavItem {
  label: string;
  path: string;
  /** Clase de PrimeIcons. El ícono es decorativo: el nombre lo da el texto. */
  icon: string;
}

/** Menú lateral (layout de Sakai, T-19). Login, cambio de contraseña y cierre de mes no figuran: se llega a ellos desde otras pantallas. */
const NAV_ITEMS: NavItem[] = [
  { label: 'Presupuesto', path: '/presupuesto', icon: 'pi-wallet' },
  { label: 'Conceptos', path: '/conceptos', icon: 'pi-list' },
  { label: 'Cuentas', path: '/cuentas', icon: 'pi-building-columns' },
  { label: 'Categorías', path: '/categorias', icon: 'pi-tags' },
  { label: 'Transferencias', path: '/transferencias', icon: 'pi-arrow-right-arrow-left' },
  { label: 'Flujo de caja', path: '/flujo-de-caja', icon: 'pi-money-bill' },
  { label: 'Proyección', path: '/proyeccion', icon: 'pi-chart-line' },
  { label: 'Varios meses', path: '/planificacion', icon: 'pi-table' },
];

@Component({
  selector: 'app-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, ToastModule, UserMenu],
  templateUrl: './layout.html',
})
export class Layout implements OnInit {
  private readonly auth = inject(AuthService);
  protected readonly navItems = NAV_ITEMS;

  ngOnInit(): void {
    // El menú de usuario muestra el email, que la sesión guardada no incluye.
    this.auth.loadUser();
  }
}
