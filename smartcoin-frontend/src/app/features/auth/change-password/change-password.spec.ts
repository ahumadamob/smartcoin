import { Location } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ChangePassword } from './change-password';
import { ChangePasswordForm } from './change-password-form';

describe('ChangePassword', () => {
  function setup(mandatory: boolean, state: unknown = null) {
    const auth = { changePassword: vi.fn(), logout: vi.fn() };
    const router = { navigateByUrl: vi.fn().mockResolvedValue(true) };
    const snackBar = { open: vi.fn() };
    TestBed.configureTestingModule({
      imports: [ChangePassword],
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: ActivatedRoute, useValue: { snapshot: { data: { mandatory } } } },
        { provide: Location, useValue: { getState: () => state } },
      ],
    });
    const fixture = TestBed.createComponent(ChangePassword);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const button = (name: string) =>
      Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === name);
    const finishChange = () => {
      fixture.debugElement.query(By.directive(ChangePasswordForm)).componentInstance.changed.emit();
    };
    return { auth, router, snackBar, root, button, finishChange };
  }

  describe('obligatorio', () => {
    it('avisa que el cambio es obligatorio y su única salida es cerrar la sesión', () => {
      const { root, button, auth } = setup(true);

      expect(root.querySelector('h1')?.textContent).toContain('Cambiar contraseña');
      expect(root.textContent).toContain(
        'Tenés que cambiar la contraseña inicial antes de seguir.',
      );
      expect(button('Cancelar')).toBeUndefined();
      button('Cerrar sesión')!.click();
      expect(auth.logout).toHaveBeenCalledOnce();
    });

    it('es la página principal, porque no está dentro del layout', () => {
      const { root } = setup(true);

      expect(root.querySelector('[role="main"]')).not.toBeNull();
    });

    it('al cambiarla confirma y va al presupuesto, aunque venga un returnUrl', () => {
      const { router, snackBar, finishChange } = setup(true, { returnUrl: '/cuentas' });

      finishChange();

      expect(snackBar.open).toHaveBeenCalledWith(
        'Contraseña cambiada.',
        'Cerrar',
        expect.anything(),
      );
      expect(router.navigateByUrl).toHaveBeenCalledWith('/presupuesto');
    });
  });

  describe('voluntario', () => {
    it('no avisa de obligatoriedad, no ofrece cerrar sesión y tiene "Cancelar"', () => {
      const { root, button } = setup(false);

      expect(root.textContent).not.toContain('Tenés que cambiar');
      expect(button('Cerrar sesión')).toBeUndefined();
      expect(button('Cancelar')).toBeDefined();
      expect(root.querySelector('[role="main"]')).toBeNull();
    });

    it('usa el mismo formulario, con sus tres campos', () => {
      const { root } = setup(false);

      const labels = Array.from(root.querySelectorAll('mat-label')).map((l) => l.textContent);
      expect(labels).toEqual([
        'Contraseña actual',
        'Contraseña nueva',
        'Repetí la contraseña nueva',
      ]);
    });

    it('"Cancelar" vuelve a donde estaba', () => {
      const { router, button } = setup(false, { returnUrl: '/cuentas' });

      button('Cancelar')!.click();

      expect(router.navigateByUrl).toHaveBeenCalledWith('/cuentas');
    });

    it('al cambiarla confirma y vuelve a donde estaba, conservando el período', () => {
      const { router, snackBar, finishChange } = setup(false, {
        returnUrl: '/presupuesto/2026-11?vista=resumen',
      });

      finishChange();

      expect(snackBar.open).toHaveBeenCalledWith(
        'Contraseña cambiada.',
        'Cerrar',
        expect.anything(),
      );
      expect(router.navigateByUrl).toHaveBeenCalledWith('/presupuesto/2026-11?vista=resumen');
    });

    it.each([
      ['sin estado', null],
      ['sin returnUrl', {}],
      ['returnUrl que no es texto', { returnUrl: 42 }],
      ['una URL externa', { returnUrl: 'https://otro.sitio/' }],
      ['una URL sin esquema que sale del sitio', { returnUrl: '//otro.sitio/' }],
      ['una ruta relativa', { returnUrl: 'cuentas' }],
      ['esta misma pantalla', { returnUrl: '/cambiar-contrasena' }],
    ])('si el origen es %s, vuelve al presupuesto', (_caso, state) => {
      const { router, button } = setup(false, state);

      button('Cancelar')!.click();

      expect(router.navigateByUrl).toHaveBeenCalledWith('/presupuesto');
    });
  });
});
