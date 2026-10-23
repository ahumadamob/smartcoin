import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AccountResponse, Transfer, TransferenciasService } from '../../api';
import { TransferForm, todayIso } from './transfer-form';

const FREE = { editable: true, reason: null };

function account(id: number, name: string, currency: 'ARS' | 'USD'): AccountResponse {
  return {
    id,
    name,
    type: 'BANK',
    currency,
    openingDate: '2026-08-01',
    initialBalance: 0,
    currentBalance: 0,
    editability: { currency: FREE, initialBalance: FREE, openingDate: FREE },
  };
}

const ACCOUNTS = [account(1, 'Banco', 'ARS'), account(2, 'Efectivo', 'ARS'), account(3, 'Dólares', 'USD')];

const CREATED: Transfer = {
  id: 31,
  date: '2026-10-15',
  sourceAccountId: 1,
  sourceAccountName: 'Banco',
  sourceCurrency: 'ARS',
  sourceAmount: 50000,
  targetAccountId: 2,
  targetAccountName: 'Efectivo',
  targetCurrency: 'ARS',
  targetAmount: 50000,
  editable: true,
};

describe('todayIso', () => {
  it('da la fecha local con ceros', () => {
    expect(todayIso(new Date(2026, 0, 5))).toBe('2026-01-05');
    expect(todayIso(new Date(2026, 11, 31))).toBe('2026-12-31');
  });
});

describe('TransferForm', () => {
  let fixture: ComponentFixture<TransferForm>;
  let api: { createTransfer: ReturnType<typeof vi.fn>; updateTransfer: ReturnType<typeof vi.fn> };
  let saved: ReturnType<typeof vi.fn<(t: Transfer) => void>>;

  beforeEach(async () => {
    api = { createTransfer: vi.fn().mockReturnValue(of(CREATED)), updateTransfer: vi.fn().mockReturnValue(of(CREATED)) };
    await TestBed.configureTestingModule({
      imports: [TransferForm],
      providers: [{ provide: TransferenciasService, useValue: api }],
    }).compileComponents();
    fixture = TestBed.createComponent(TransferForm);
    fixture.componentRef.setInput('accounts', ACCOUNTS);
    saved = vi.fn<(t: Transfer) => void>();
    fixture.componentInstance.saved.subscribe((t) => saved(t));
    fixture.detectChanges();
  });

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const form = () => fixture.componentInstance['form'];
  const field = (label: string) =>
    Array.from(root().querySelectorAll('.field')).find((f) => f.querySelector('label')?.textContent?.includes(label))!;
  const type = (label: string, value: string) => {
    const el = field(label).querySelector('input')!;
    el.value = value;
    el.dispatchEvent(new Event('input'));
    el.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
  };
  const submit = () => {
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  };
  const fill = () => {
    form().controls.sourceAccountId.setValue(1);
    form().controls.targetAccountId.setValue(2);
    form().controls.date.setValue('2026-10-15');
    type('Monto', '50.000,00');
  };

  it('arranca con la fecha de hoy', () => {
    expect(form().controls.date.value).toBe(todayIso());
  });

  it('ofrece como destino todas las cuentas distintas del origen, también las de otra moneda', () => {
    expect(fixture.componentInstance['targets']()).toEqual([]);
    form().controls.sourceAccountId.setValue(1);
    expect(fixture.componentInstance['targets']().map((a: AccountResponse) => a.id)).toEqual([2, 3]);
  });

  describe('con monedas distintas', () => {
    const CROSS: Transfer = { ...CREATED, targetAccountId: 3, targetAccountName: 'Dólares', targetCurrency: 'USD', targetAmount: 1000 };

    const fillCross = (source: number, target: number) => {
      form().controls.sourceAccountId.setValue(source);
      form().controls.targetAccountId.setValue(target);
      form().controls.date.setValue('2026-10-15');
      fixture.detectChanges();
    };

    it('pide dos montos, el de pesos y el de dólares, cada uno con su símbolo', () => {
      fillCross(1, 3);

      expect(field('Monto en pesos').textContent).toContain('$');
      expect(field('Monto en dólares').textContent).toContain('US$');
      expect(text()).toContain('Compra de dólares');
    });

    it('con la misma moneda sigue pidiendo un solo monto', () => {
      fillCross(1, 2);

      expect(root().textContent).not.toContain('Monto en dólares');
      expect(field('Monto')).toBeTruthy();
      expect(form().controls.usdAmount.disabled).toBe(true);
    });

    it('una compra manda el de pesos como origen y el de dólares como destino', () => {
      api.createTransfer.mockReturnValue(of(CROSS));
      fillCross(1, 3);
      type('Monto en pesos', '1.250.000,00');
      type('Monto en dólares', '1.000,00');
      submit();

      expect(api.createTransfer).toHaveBeenCalledWith({
        sourceAccountId: 1,
        targetAccountId: 3,
        date: '2026-10-15',
        sourceAmount: 1250000,
        targetAmount: 1000,
      });
      expect(saved).toHaveBeenCalledWith(CROSS);
    });

    it('una venta manda el de dólares como origen y el de pesos como destino', () => {
      fillCross(3, 1);
      type('Monto en pesos', '610.000,00');
      type('Monto en dólares', '500,00');
      submit();

      expect(text()).toContain('Venta de dólares');
      expect(api.createTransfer.mock.calls[0][0]).toMatchObject({
        sourceAccountId: 3,
        targetAccountId: 1,
        sourceAmount: 500,
        targetAmount: 610000,
      });
    });

    it('valida el formato de los dos montos sin llamar a la API', () => {
      fillCross(1, 3);
      submit();
      expect(text()).toContain('Ingresá el monto en pesos.');
      expect(text()).toContain('Ingresá el monto en dólares.');

      type('Monto en pesos', '1000,00');
      type('Monto en dólares', '0');
      expect(text()).toContain('mayor que 0');
      expect(api.createTransfer).not.toHaveBeenCalled();
    });

    it('al volver a una cuenta de la misma moneda el monto en dólares deja de contar', () => {
      fillCross(1, 3);
      type('Monto en dólares', 'abc');
      form().controls.targetAccountId.setValue(2);
      fixture.detectChanges();
      type('Monto', '10,00');

      expect(form().valid).toBe(true);
    });
  });

  it('al cambiar el origen descarta un destino que ya no corresponde', () => {
    form().controls.sourceAccountId.setValue(1);
    form().controls.targetAccountId.setValue(2);
    form().controls.sourceAccountId.setValue(2);
    expect(form().controls.targetAccountId.value).toBeNull();
  });

  it('manda el mismo monto como origen y destino, con la coma decimal convertida', () => {
    fill();
    form().controls.note.setValue('  Extracción ');
    submit();

    expect(api.createTransfer).toHaveBeenCalledWith({
      sourceAccountId: 1,
      targetAccountId: 2,
      date: '2026-10-15',
      sourceAmount: 50000,
      targetAmount: 50000,
      note: 'Extracción',
    });
    expect(saved).toHaveBeenCalledWith(CREATED);
  });

  it('sin nota no manda el campo', () => {
    fill();
    submit();

    expect(api.createTransfer.mock.calls[0][0]).not.toHaveProperty('note');
  });

  it('valida el formato sin llamar a la API', () => {
    submit();
    expect(api.createTransfer).not.toHaveBeenCalled();
    expect(text()).toContain('Elegí la cuenta de origen.');
    expect(text()).toContain('Ingresá el monto.');

    type('Monto', '12.5');
    expect(text()).toContain('coma decimal');
    type('Monto', '0');
    expect(text()).toContain('mayor que 0');
    type('Monto', '1,234');
    expect(text()).toContain('coma decimal');
    expect(api.createTransfer).not.toHaveBeenCalled();
  });

  it('muestra el error del backend sin perder lo cargado', () => {
    api.createTransfer.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { code: 'DATE_OUT_OF_RANGE', detail: 'La fecha no puede ser posterior a hoy (15/10/2026).' },
          }),
      ),
    );
    fill();
    form().controls.note.setValue('Nota');
    submit();

    expect(text()).toContain('La fecha no puede ser posterior a hoy (15/10/2026).');
    expect(form().controls.amount.value).toBe('50.000,00');
    expect(form().controls.note.value).toBe('Nota');
    expect(form().controls.sourceAccountId.value).toBe(1);
    expect(form().controls.targetAccountId.value).toBe(2);
    expect(saved).not.toHaveBeenCalled();
  });

  it('traduce un código sin detalle propio', () => {
    api.createTransfer.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'TRANSFER_AMOUNTS_MISMATCH' } })),
    );
    fill();
    submit();

    expect(text()).toContain('el monto que sale y el que entra tienen que ser iguales');
  });

  it('al guardar vacía el monto y la nota y deja las cuentas y la fecha', () => {
    fill();
    form().controls.note.setValue('Nota');
    submit();

    expect(form().controls.amount.value).toBe('');
    expect(form().controls.note.value).toBe('');
    expect(form().controls.sourceAccountId.value).toBe(1);
    expect(form().controls.date.value).toBe('2026-10-15');
  });

  describe('edición (HU-29)', () => {
    const form = () => fixture.componentInstance['form'];

    function edit(transfer: Transfer) {
      fixture.componentRef.setInput('transfer', transfer);
      fixture.detectChanges();
    }

    it('carga los valores de la transferencia y guarda con PUT sin crear', () => {
      edit({ ...CREATED, note: 'Extracción' });

      expect(form().getRawValue()).toMatchObject({
        sourceAccountId: 1,
        targetAccountId: 2,
        date: '2026-10-15',
        amount: '50000,00',
        note: 'Extracción',
      });

      form().controls.amount.setValue('45000,00');
      fixture.componentInstance['submit']();

      expect(api.createTransfer).not.toHaveBeenCalled();
      expect(api.updateTransfer).toHaveBeenCalledWith(31, {
        sourceAccountId: 1,
        targetAccountId: 2,
        date: '2026-10-15',
        sourceAmount: 45000,
        targetAmount: 45000,
        note: 'Extracción',
      });
      expect(saved).toHaveBeenCalledWith(CREATED);
    });

    it('en una compra carga el monto de pesos y el de dólares, cada uno en su campo', () => {
      edit({
        ...CREATED,
        targetAccountId: 3,
        targetAccountName: 'Dólares',
        targetCurrency: 'USD',
        sourceAmount: 1250000,
        targetAmount: 1000,
      });

      expect(form().controls.amount.value).toBe('1250000,00');
      expect(form().controls.usdAmount.value).toBe('1000,00');
      expect(form().controls.usdAmount.enabled).toBe(true);
    });

    it('en una venta el de pesos sale del destino y el de dólares del origen', () => {
      edit({
        ...CREATED,
        sourceAccountId: 3,
        sourceAccountName: 'Dólares',
        sourceCurrency: 'USD',
        sourceAmount: 500,
        targetAccountId: 1,
        targetAccountName: 'Banco',
        targetAmount: 610000,
      });

      expect(form().controls.amount.value).toBe('610000,00');
      expect(form().controls.usdAmount.value).toBe('500,00');

      fixture.componentInstance['submit']();
      expect(api.updateTransfer).toHaveBeenCalledWith(
        31,
        expect.objectContaining({ sourceAmount: 500, targetAmount: 610000 }),
      );
    });

    it('al pasar a una cuenta de otra moneda pide el segundo monto', () => {
      edit(CREATED);
      expect(form().controls.usdAmount.disabled).toBe(true);

      form().controls.targetAccountId.setValue(3);

      expect(form().controls.usdAmount.enabled).toBe(true);
      fixture.componentInstance['submit']();
      expect(api.updateTransfer).not.toHaveBeenCalled();
    });

    it('muestra el error del backend y conserva lo cargado', () => {
      api.updateTransfer.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { code: 'DATE_OUT_OF_RANGE', detail: 'La fecha no puede ser posterior a hoy (15/10/2026).' },
            }),
        ),
      );
      edit(CREATED);
      form().controls.date.setValue('2026-10-20');

      fixture.componentInstance['submit']();
      fixture.detectChanges();

      expect((fixture.nativeElement as HTMLElement).textContent).toContain('no puede ser posterior a hoy');
      expect(form().controls.date.value).toBe('2026-10-20');
      expect(saved).not.toHaveBeenCalled();
    });
  });
});
