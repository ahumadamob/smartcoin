import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
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
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    /** Abre el menú como lo haría el usuario y devuelve sus opciones (el menú se dibuja en el `body`). */
    async function open(): Promise<HTMLElement[]> {
      trigger.click();
      fixture.detectChanges();
      await fixture.whenStable();
      return Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]'));
    }
    return { auth, router, fixture, trigger, open };
  }

  afterEach(() => document.querySelectorAll('[role="menu"]').forEach((menu) => menu.closest('.p-menu')?.remove()));

  it('el disparador muestra el email del usuario', () => {
    const { trigger } = setup();

    expect(trigger.textContent?.trim()).toBe('persona@ejemplo.com');
  });

  it('mientras no se conoce el email muestra "Mi cuenta"', () => {
    const { trigger } = setup(null);

    expect(trigger.textContent?.trim()).toBe('Mi cuenta');
  });

  it('ofrece "Cambiar contraseña" y "Cerrar sesión"', async () => {
    const { open } = setup();

    const items = await open();

    expect(items.map((i) => i.textContent?.trim())).toEqual(['Cambiar contraseña', 'Cerrar sesión']);
  });

  it('"Cambiar contraseña" navega a la pantalla recordando dónde estaba', async () => {
    const { open, router } = setup();

    const [change] = await open();
    change.querySelector<HTMLElement>('a, [data-pc-section="itemcontent"]')?.click();

    expect(router.navigate).toHaveBeenCalledWith(['/cambiar-contrasena'], {
      state: { returnUrl: '/cuentas' },
    });
  });

  it('"Cerrar sesión" cierra la sesión', async () => {
    const { open, auth } = setup();

    const [, logout] = await open();
    logout.querySelector<HTMLElement>('a, [data-pc-section="itemcontent"]')?.click();

    expect(auth.logout).toHaveBeenCalledOnce();
  });

  it('es un botón enfocable que anuncia un menú con dos opciones', async () => {
    const { trigger, open, fixture } = setup();

    trigger.focus();
    const items = await open();
    fixture.detectChanges();

    expect(items).toHaveLength(2);
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(document.getElementById(trigger.getAttribute('aria-controls') ?? '')).not.toBeNull();
  });

  it('los íconos son decorativos', () => {
    const { trigger } = setup();

    const icons = Array.from(trigger.querySelectorAll('i'));
    expect(icons.length).toBeGreaterThan(0);
    expect(icons.every((icon) => icon.getAttribute('aria-hidden') === 'true')).toBe(true);
  });
});
