import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { ChangePasswordForm } from './change-password-form';

const OK = { token: 'nuevo', expiresAt: '2999-01-01T00:00:00Z', mustChangePassword: false };

describe('ChangePasswordForm', () => {
  let fixture: ComponentFixture<ChangePasswordForm>;
  let auth: { changePassword: ReturnType<typeof vi.fn> };
  let changed: ReturnType<typeof vi.fn<() => void>>;

  beforeEach(async () => {
    auth = { changePassword: vi.fn().mockReturnValue(of(OK)) };
    await TestBed.configureTestingModule({
      imports: [ChangePasswordForm],
      providers: [{ provide: AuthService, useValue: auth }],
    }).compileComponents();
    fixture = TestBed.createComponent(ChangePasswordForm);
    changed = vi.fn<() => void>();
    fixture.componentInstance.changed.subscribe(() => changed());
    fixture.detectChanges();
  });

  const root = () => fixture.nativeElement as HTMLElement;
  const input = (label: string) =>
    Array.from(root().querySelectorAll('mat-form-field'))
      .find((f) => f.querySelector('label')?.textContent?.includes(label))!
      .querySelector('input')!;
  const type = (label: string, value: string) => {
    const field = input(label);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
  };
  const fill = (current: string, next: string, repeat: string) => {
    type('Contraseña actual', current);
    type('Contraseña nueva', next);
    type('Repetí', repeat);
  };
  const submit = () => {
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  };
  const text = () => root().textContent ?? '';

  it('tiene los tres campos con etiqueta, de contraseña y con autocompletado correcto', () => {
    expect(input('Contraseña actual').type).toBe('password');
    expect(input('Contraseña actual').autocomplete).toBe('current-password');
    expect(input('Contraseña nueva').autocomplete).toBe('new-password');
    expect(input('Repetí la contraseña nueva').autocomplete).toBe('new-password');
    expect(root().querySelectorAll('input')).toHaveLength(3);
  });

  it('con todo vacío marca los obligatorios y no llama a la API', () => {
    submit();

    expect(text()).toContain('Ingresá tu contraseña actual.');
    expect(text()).toContain('Ingresá la contraseña nueva.');
    expect(text()).toContain('Repetí la contraseña nueva.');
    expect(auth.changePassword).not.toHaveBeenCalled();
  });

  it('una nueva de menos de 10 caracteres no se envía', () => {
    fill('la-actual-123', 'corta-123', 'corta-123');
    submit();

    expect(text()).toContain('La contraseña nueva debe tener al menos 10 caracteres.');
    expect(auth.changePassword).not.toHaveBeenCalled();
  });

  it('con exactamente 10 caracteres el formato es válido', () => {
    fill('la-actual-123', '1234567890', '1234567890');
    submit();

    expect(auth.changePassword).toHaveBeenCalledWith('la-actual-123', '1234567890');
  });

  it('una repetición distinta no se envía', () => {
    fill('la-actual-123', 'la-nueva-1234', 'la-nueva-9999');
    submit();

    expect(text()).toContain('Las contraseñas no coinciden.');
    expect(auth.changePassword).not.toHaveBeenCalled();
  });

  it('si se corrige la nueva, la repetición se vuelve a comparar', () => {
    fill('la-actual-123', 'la-nueva-1234', 'la-nueva-9999');
    expect(text()).toContain('Las contraseñas no coinciden.');

    type('Contraseña nueva', 'la-nueva-9999');

    expect(text()).not.toContain('Las contraseñas no coinciden.');
  });

  it('con datos válidos cambia la contraseña y avisa con `changed`', () => {
    fill('la-actual-123', 'la-nueva-1234', 'la-nueva-1234');
    submit();

    expect(auth.changePassword).toHaveBeenCalledWith('la-actual-123', 'la-nueva-1234');
    expect(changed).toHaveBeenCalledOnce();
    expect(root().querySelector('[role="alert"]')).toBeNull();
  });

  it('con la contraseña actual incorrecta muestra el error y no avisa', () => {
    auth.changePassword.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { code: 'INVALID_CURRENT_PASSWORD', detail: 'texto del backend' },
          }),
      ),
    );
    fill('equivocada-123', 'la-nueva-1234', 'la-nueva-1234');
    submit();

    expect(root().querySelector('[role="alert"]')?.textContent).toBe(
      'La contraseña actual es incorrecta.',
    );
    expect(changed).not.toHaveBeenCalled();
    expect(input('Contraseña nueva').value).toBe('la-nueva-1234');
  });

  it('muestra el detail del backend cuando la nueva es igual a la actual', () => {
    auth.changePassword.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: {
              code: 'VALIDATION_ERROR',
              detail: 'La contraseña nueva debe ser distinta de la actual.',
            },
          }),
      ),
    );
    fill('la-misma-1234', 'la-misma-1234', 'la-misma-1234');
    submit();

    expect(root().querySelector('[role="alert"]')?.textContent).toBe(
      'La contraseña nueva debe ser distinta de la actual.',
    );
  });

  it('el error desaparece al volver a enviar', () => {
    auth.changePassword.mockReturnValueOnce(
      throwError(
        () => new HttpErrorResponse({ status: 400, error: { code: 'INVALID_CURRENT_PASSWORD' } }),
      ),
    );
    fill('equivocada-123', 'la-nueva-1234', 'la-nueva-1234');
    submit();
    expect(root().querySelector('[role="alert"]')).not.toBeNull();

    type('Contraseña actual', 'la-actual-123');
    submit();

    expect(root().querySelector('[role="alert"]')).toBeNull();
    expect(changed).toHaveBeenCalledOnce();
  });
});
