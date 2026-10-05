import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatMenuHarness } from '@angular/material/menu/testing';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { UserMenu } from './user-menu';

describe('UserMenu', () => {
  function setup(email: string | null = 'persona@ejemplo.com') {
    const auth = { email: signal(email), logout: vi.fn() };
    const router = { url: '/cuentas', navigate: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
      imports: [UserMenu],
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
      ],
    });
    const fixture = TestBed.createComponent(UserMenu);
    fixture.detectChanges();
    const loader = TestbedHarnessEnvironment.loader(fixture);
    return { auth, router, fixture, menu: () => loader.getHarness(MatMenuHarness) };
  }

  it('el disparador muestra el email del usuario', async () => {
    const { menu } = setup();

    expect(await (await menu()).getTriggerText()).toBe('persona@ejemplo.com');
  });

  it('mientras no se conoce el email muestra "Mi cuenta"', async () => {
    const { menu } = setup(null);

    expect(await (await menu()).getTriggerText()).toBe('Mi cuenta');
  });

  it('ofrece "Cambiar contraseña" y "Cerrar sesión"', async () => {
    const { menu } = setup();
    const harness = await menu();

    await harness.open();

    const items = await harness.getItems();
    expect(await Promise.all(items.map((i) => i.getText()))).toEqual([
      'Cambiar contraseña',
      'Cerrar sesión',
    ]);
  });

  it('"Cambiar contraseña" navega a la pantalla recordando dónde estaba', async () => {
    const { menu, router } = setup();
    const harness = await menu();

    await harness.clickItem({ text: 'Cambiar contraseña' });

    expect(router.navigate).toHaveBeenCalledWith(['/cambiar-contrasena'], {
      state: { returnUrl: '/cuentas' },
    });
  });

  it('"Cerrar sesión" cierra la sesión', async () => {
    const { menu, auth } = setup();
    const harness = await menu();

    await harness.clickItem({ text: 'Cerrar sesión' });

    expect(auth.logout).toHaveBeenCalledOnce();
  });

  it('es un botón enfocable que anuncia un menú con dos opciones', async () => {
    const { fixture } = setup();
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('button');

    trigger.focus();
    trigger.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const items = document.querySelectorAll('[role="menuitem"]');
    expect(items).toHaveLength(2);
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
  });
});
