import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { AuthService } from '../auth/auth.service';
import { Layout } from './layout';

describe('Layout', () => {
  function setup(email: string | null) {
    const auth = { email: signal(email), loadUser: vi.fn(), logout: vi.fn() };
    TestBed.configureTestingModule({
      imports: [Layout],
      providers: [provideRouter([]), MessageService, { provide: AuthService, useValue: auth }],
    });
    const fixture = TestBed.createComponent(Layout);
    fixture.detectChanges();
    return { auth, fixture, root: fixture.nativeElement as HTMLElement };
  }

  it('al abrirse carga el email del usuario', () => {
    const { auth, root } = setup(null);

    expect(auth.loadUser).toHaveBeenCalledOnce();
    expect(root.querySelector('app-user-menu')).not.toBeNull();
    expect(root.querySelector('nav')).not.toBeNull();
  });

  it('el menú lateral tiene las mismas entradas y rutas que antes, con íconos decorativos', () => {
    const { root } = setup(null);

    const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('nav[aria-label="Principal"] a'));
    expect(links.map((a) => [a.textContent?.trim(), a.getAttribute('href')])).toEqual([
      ['Presupuesto', '/presupuesto'],
      ['Conceptos', '/conceptos'],
      ['Cuentas', '/cuentas'],
      ['Categorías', '/categorias'],
      ['Transferencias', '/transferencias'],
      ['Flujo de caja', '/flujo-de-caja'],
      ['Proyección', '/proyeccion'],
      ['Varios meses', '/planificacion'],
    ]);
    expect(Array.from(root.querySelectorAll('nav i')).every((i) => i.getAttribute('aria-hidden') === 'true')).toBe(true);
  });

  it('ya no tiene el botón suelto de cerrar sesión: está en el menú de usuario, en la barra superior', async () => {
    const { root, fixture } = setup('persona@ejemplo.com');

    const buttons: HTMLButtonElement[] = Array.from(root.querySelectorAll('button'));
    expect(buttons.map((b) => b.textContent?.trim())).toEqual(['persona@ejemplo.com']);
    expect(buttons[0].closest('header')).not.toBeNull();

    buttons[0].click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.querySelectorAll('[role="menuitem"]')).toHaveLength(2);
    document.querySelectorAll('.cdk-overlay-container').forEach((overlay) => overlay.remove());
  });
});
