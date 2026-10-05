import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { UserMenu } from './user-menu';

interface NavItem {
  label: string;
  path: string;
}

/** Menú lateral. Login, cambio de contraseña y cierre de mes no figuran: se llega a ellos desde otras pantallas. */
const NAV_ITEMS: NavItem[] = [
  { label: 'Presupuesto', path: '/presupuesto' },
  { label: 'Conceptos', path: '/conceptos' },
  { label: 'Cuentas', path: '/cuentas' },
  { label: 'Categorías', path: '/categorias' },
  { label: 'Transferencias', path: '/transferencias' },
  { label: 'Flujo de caja', path: '/flujo-de-caja' },
  { label: 'Proyección', path: '/proyeccion' },
  { label: 'Varios meses', path: '/planificacion' },
];

@Component({
  selector: 'app-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatListModule,
    MatSidenavModule,
    MatToolbarModule,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    UserMenu,
  ],
  templateUrl: './layout.html',
  styleUrl: './layout.scss',
})
export class Layout implements OnInit {
  private readonly auth = inject(AuthService);
  protected readonly navItems = NAV_ITEMS;

  ngOnInit(): void {
    // El menú de usuario muestra el email, que la sesión guardada no incluye.
    this.auth.loadUser();
  }
}
