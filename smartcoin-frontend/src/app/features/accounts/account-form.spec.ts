import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AccountRequest, AccountResponse, CuentasService } from '../../api';
import { AccountForm } from './account-form';

const SUGGESTED = '2026-08-01';

const FREE = { editable: true, reason: null };

function account(overrides: Partial<AccountResponse> = {}): AccountResponse {
  return {
    id: 5,
    name: 'Banco',
    type: 'BANK',
    currency: 'ARS',
    openingDate: '2026-08-15',
    initialBalance: 1500.5,
    currentBalance: 1500.5,
    editability: { currency: FREE, initialBalance: FREE, openingDate: FREE },
    ...overrides,
  };
}

const LOCKED_CURRENCY = {
  editable: false,
  reason: 'La moneda no se puede cambiar: la cuenta ya está usada.',
};
const LOCKED_BY_CLOSINGS = {
  editable: false,
  reason: 'No se puede cambiar: la cuenta ya tiene cierres de mes.',
};

describe('AccountForm', () => {
  let fixture: ComponentFixture<AccountForm>;
  let api: { createAccount: ReturnType<typeof vi.fn>; updateAccount: ReturnType<typeof vi.fn> };
  let saved: ReturnType<typeof vi.fn<(a: AccountResponse) => void>>;
  let cancelled: ReturnType<typeof vi.fn<() => void>>;

  beforeEach(async () => {
    api = {
      createAccount: vi.fn().mockReturnValue(of(account())),
      updateAccount: vi.fn().mockReturnValue(of(account())),
    };
    await TestBed.configureTestingModule({
      imports: [AccountForm],
      providers: [{ provide: CuentasService, useValue: api }],
    }).compileComponents();
    fixture = TestBed.createComponent(AccountForm);
    fixture.componentRef.setInput('suggestedOpeningDate', SUGGESTED);
    saved = vi.fn<(a: AccountResponse) => void>();
    cancelled = vi.fn<() => void>();
    fixture.componentInstance.saved.subscribe((a) => saved(a));
    fixture.componentInstance.cancelled.subscribe(() => cancelled());
    fixture.detectChanges();
  });

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const form = () => fixture.componentInstance['form'];
  const field = (label: string) =>
    Array.from(root().querySelectorAll('mat-form-field')).find((f) =>
      f.querySelector('label')?.textContent?.includes(label),
    )!;
  const input = (label: string) => field(label).querySelector('input')!;
  const type = (label: string, value: string) => {
    const el = input(label);
    el.value = value;
    el.dispatchEvent(new Event('input'));
    el.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
  };
  const submit = () => {
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  };
  const edit = (a: AccountResponse) => {
    fixture.componentRef.setInput('account', a);
    fixture.detectChanges();
  };
  const fillNew = (balance: string) => {
    type('Nombre', 'Caja de ahorro');
    form().patchValue({ type: 'CASH', currency: 'USD' });
    type('Saldo inicial', balance);
  };

  describe('alta', () => {
    it('tiene todos los campos con etiqueta y sugiere el primer día del período inicial', () => {
      for (const label of ['Nombre', 'Tipo', 'Moneda', 'Fecha de apertura', 'Saldo inicial']) {
        expect(field(label), label).toBeTruthy();
      }
      expect(input('Fecha de apertura').type).toBe('date');
      expect(input('Fecha de apertura').value).toBe(SUGGESTED);
      expect(text()).toContain('Sugerida: el primer día de tu período inicial.');
    });

    it('con todo vacío marca los obligatorios y no llama a la API', () => {
      submit();

      expect(text()).toContain('Ingresá el nombre de la cuenta.');
      expect(text()).toContain('Elegí el tipo de cuenta.');
      expect(text()).toContain('Elegí la moneda.');
      expect(text()).toContain('Ingresá el saldo inicial.');
      expect(api.createAccount).not.toHaveBeenCalled();
    });

    it('un nombre de solo espacios no vale', () => {
      type('Nombre', '   ');
      submit();

      expect(text()).toContain('Ingresá el nombre de la cuenta.');
      expect(api.createAccount).not.toHaveBeenCalled();
    });

    it('convierte el saldo con coma decimal a número antes de enviar', () => {
      fillNew('1.234,50');
      submit();

      expect(api.createAccount).toHaveBeenCalledWith({
        name: 'Caja de ahorro',
        type: 'CASH',
        currency: 'USD',
        openingDate: SUGGESTED,
        initialBalance: 1234.5,
      } satisfies AccountRequest);
    });

    it('acepta un saldo negativo', () => {
      fillNew('-250,5');
      submit();

      expect(api.createAccount.mock.calls[0][0].initialBalance).toBe(-250.5);
    });

    it('recorta los espacios del nombre', () => {
      fillNew('0');
      type('Nombre', '  Caja de ahorro ');
      submit();

      expect(api.createAccount.mock.calls[0][0].name).toBe('Caja de ahorro');
    });

    it.each(['1.5', '1,234,5', 'abc', '10,123'])('un saldo "%s" no se envía', (balance) => {
      fillNew(balance);
      submit();

      expect(text()).toContain('Escribilo con coma decimal y hasta 2 decimales');
      expect(api.createAccount).not.toHaveBeenCalled();
    });

    it('al guardar avisa con la cuenta creada', () => {
      fillNew('0');
      submit();

      expect(saved).toHaveBeenCalledWith(account());
    });

    it('muestra el error del backend con el archivo de mensajes y no avisa que guardó', () => {
      api.createAccount.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { code: 'ACCOUNT_NAME_TAKEN', detail: 'texto del backend' },
            }),
        ),
      );
      fillNew('0');
      submit();

      expect(root().querySelector('[role="alert"]')?.textContent).toContain('Ya tenés una cuenta con ese nombre.');
      expect(saved).not.toHaveBeenCalled();
    });

    it('un VALIDATION_ERROR (fecha fuera de rango) muestra el detalle del backend', () => {
      api.createAccount.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { code: 'VALIDATION_ERROR', detail: 'La fecha de apertura no puede ser futura.' },
            }),
        ),
      );
      fillNew('0');
      submit();

      expect(root().querySelector('[role="alert"]')?.textContent).toContain(
        'La fecha de apertura no puede ser futura.',
      );
    });

    it('Cancelar avisa y no llama a la API', () => {
      Array.from(root().querySelectorAll('button'))
        .find((b) => b.textContent?.includes('Cancelar'))!
        .click();

      expect(cancelled).toHaveBeenCalledOnce();
      expect(api.createAccount).not.toHaveBeenCalled();
    });
  });

  describe('edición', () => {
    it('carga los datos de la cuenta, con el saldo en coma decimal', () => {
      edit(account({ initialBalance: -1500.5 }));

      expect(input('Nombre').value).toBe('Banco');
      expect(input('Fecha de apertura').value).toBe('2026-08-15');
      expect(input('Saldo inicial').value).toBe('-1500,50');
      expect(form().controls.type.value).toBe('BANK');
      expect(form().controls.currency.value).toBe('ARS');
      expect(text()).not.toContain('Sugerida');
    });

    it('con todo editable, ningún campo está deshabilitado y no hay motivos', () => {
      edit(account());

      expect(form().enabled).toBe(true);
      expect(root().querySelectorAll('.reason')).toHaveLength(0);
    });

    it('los campos no editables se ven deshabilitados con el motivo del backend', () => {
      edit(
        account({
          editability: {
            currency: LOCKED_CURRENCY,
            initialBalance: LOCKED_BY_CLOSINGS,
            openingDate: LOCKED_BY_CLOSINGS,
          },
        }),
      );

      expect(form().controls.currency.disabled).toBe(true);
      expect(form().controls.initialBalance.disabled).toBe(true);
      expect(form().controls.openingDate.disabled).toBe(true);
      expect(input('Saldo inicial').disabled).toBe(true);
      expect(input('Fecha de apertura').disabled).toBe(true);
      expect(field('Moneda').textContent).toContain(LOCKED_CURRENCY.reason);
      expect(field('Saldo inicial').textContent).toContain(LOCKED_BY_CLOSINGS.reason);
      expect(field('Fecha de apertura').textContent).toContain(LOCKED_BY_CLOSINGS.reason);
    });

    it('el nombre y el tipo siempre se pueden editar, aunque todo lo demás esté bloqueado', () => {
      edit(
        account({
          editability: {
            currency: LOCKED_CURRENCY,
            initialBalance: LOCKED_BY_CLOSINGS,
            openingDate: LOCKED_BY_CLOSINGS,
          },
        }),
      );

      expect(form().controls.name.enabled).toBe(true);
      expect(form().controls.type.enabled).toBe(true);
      expect(input('Nombre').disabled).toBe(false);
    });

    it('solo se bloquea lo que el backend bloquea: moneda sí, saldo y fecha no', () => {
      edit(
        account({
          editability: { currency: LOCKED_CURRENCY, initialBalance: FREE, openingDate: FREE },
        }),
      );

      expect(form().controls.currency.disabled).toBe(true);
      expect(form().controls.initialBalance.enabled).toBe(true);
      expect(form().controls.openingDate.enabled).toBe(true);
      expect(root().querySelectorAll('.reason')).toHaveLength(1);
    });

    it('al guardar con campos bloqueados envía sus valores actuales y los editados', () => {
      edit(
        account({
          initialBalance: 1500.5,
    currentBalance: 1500.5,
          editability: {
            currency: LOCKED_CURRENCY,
            initialBalance: LOCKED_BY_CLOSINGS,
            openingDate: LOCKED_BY_CLOSINGS,
          },
        }),
      );
      type('Nombre', 'Banco Nación');
      submit();

      expect(api.updateAccount).toHaveBeenCalledWith(5, {
        name: 'Banco Nación',
        type: 'BANK',
        currency: 'ARS',
        openingDate: '2026-08-15',
        initialBalance: 1500.5,
      } satisfies AccountRequest);
      expect(api.createAccount).not.toHaveBeenCalled();
      expect(saved).toHaveBeenCalledOnce();
    });

    it('al pasar a editar otra cuenta se recargan los valores y los bloqueos', () => {
      edit(account({ editability: { currency: LOCKED_CURRENCY, initialBalance: FREE, openingDate: FREE } }));
      edit(account({ id: 6, name: 'Efectivo', currency: 'USD' }));

      expect(input('Nombre').value).toBe('Efectivo');
      expect(form().controls.currency.value).toBe('USD');
      expect(form().controls.currency.enabled).toBe(true);
    });

    it('al volver a un alta se limpian los datos y vuelve la fecha sugerida', () => {
      edit(account());
      fixture.componentRef.setInput('account', null);
      fixture.detectChanges();

      expect(input('Nombre').value).toBe('');
      expect(input('Fecha de apertura').value).toBe(SUGGESTED);
      expect(form().enabled).toBe(true);
    });
  });
});
