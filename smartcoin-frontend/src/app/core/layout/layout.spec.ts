import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { Layout } from './layout';

describe('Layout', () => {
  it('"Cerrar sesión" cierra la sesión', () => {
    const auth = { logout: vi.fn() };
    TestBed.configureTestingModule({
      imports: [Layout],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    const fixture = TestBed.createComponent(Layout);
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    button.click();

    expect(button.textContent).toContain('Cerrar sesión');
    expect(button.classList).toContain('mat-mdc-button-base');
    expect(auth.logout).toHaveBeenCalledOnce();
  });
});
