import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { Projection, ReportesService } from '../../api';
import { ProjectionPage } from './projection';

function projection(overrides: Partial<Projection> = {}): Projection {
  return {
    from: '2026-10',
    until: '2028-10',
    accounts: [{ id: 1, name: 'Banco', currency: 'ARS' }],
    months: [
      {
        month: '2026-10',
        balances: [{ accountId: 1, balance: 100000 }],
        subtotals: [{ currency: 'ARS', balance: 100000 }],
      },
    ],
    ...overrides,
  };
}

describe('ProjectionPage', () => {
  let fixture: ComponentFixture<ProjectionPage>;
  let getProjection: ReturnType<typeof vi.fn>;

  async function setup(result: Observable<Projection> = of(projection())) {
    registerLocaleData(localeEsAr, 'es-AR');
    getProjection = vi.fn().mockReturnValue(result);
    await TestBed.configureTestingModule({
      imports: [ProjectionPage],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ReportesService, useValue: { getProjection } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ProjectionPage);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const untilInput = () => root().querySelector<HTMLInputElement>('input')!;

  function submit() {
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  }

  it('al abrir pide la proyección sin «Hasta» y muestra el horizonte que resolvió el backend', async () => {
    await setup();

    expect(getProjection).toHaveBeenCalledWith(undefined);
    expect(untilInput().value).toBe('2028-10');
    expect(root().querySelector('app-projection-table')).not.toBeNull();
  });

  it('explica qué supone la proyección', async () => {
    await setup();

    const text = root().querySelector('[data-testid="assumption"]')!.textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('cada pendiente se cobra o paga en su cuenta prevista y en su vencimiento');
    expect(text).toContain('No incluye transferencias futuras');
  });

  it('«Ver» pide la proyección hasta el mes elegido', async () => {
    await setup();
    getProjection.mockClear();

    untilInput().value = '2027-03';
    untilInput().dispatchEvent(new Event('input'));
    submit();

    expect(getProjection).toHaveBeenCalledWith('2027-03');
  });

  it('muestra el error del backend y no deja la tabla anterior a la vista', async () => {
    await setup();
    getProjection.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { code: 'VALIDATION_ERROR', detail: 'El mes hasta no puede ser posterior al horizonte (2028-10).' },
          }),
      ),
    );

    submit();

    expect(root().querySelector('[role="alert"]')!.textContent).toContain('posterior al horizonte');
    expect(root().querySelector('app-projection-table')).toBeNull();
  });

  it('mientras calcula avisa y no muestra la tabla vieja', async () => {
    const pending = new Subject<Projection>();
    await setup(pending);

    expect(root().querySelector('[role="status"]')!.textContent).toContain('Calculando');
    expect(root().querySelector('app-projection-table')).toBeNull();
  });
});
