import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatMenuHarness } from '@angular/material/menu/testing';
import { HarnessLoader } from '@angular/cdk/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { Layout } from './layout';

describe('Layout', () => {
  it('al abrirse carga el email del usuario', () => {
    const auth = { email: signal(null), loadUser: vi.fn(), logout: vi.fn() };
    TestBed.configureTestingModule({
      imports: [Layout],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    const fixture = TestBed.createComponent(Layout);
    fixture.detectChanges();

    expect(auth.loadUser).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.querySelector('app-user-menu')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('nav')).not.toBeNull();
  });

  it('ya no tiene el botón suelto de cerrar sesión: está en el menú de usuario', async () => {
    const auth = { email: signal('persona@ejemplo.com'), loadUser: vi.fn(), logout: vi.fn() };
    TestBed.configureTestingModule({
      imports: [Layout],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    const fixture = TestBed.createComponent(Layout);
    fixture.detectChanges();
    const loader: HarnessLoader = TestbedHarnessEnvironment.loader(fixture);

    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    );
    expect(buttons.map((b) => b.textContent?.trim())).toEqual(['persona@ejemplo.com']);
    const menu = await loader.getHarness(MatMenuHarness);
    await menu.open();
    expect((await menu.getItems()).length).toBe(2);
  });
});
