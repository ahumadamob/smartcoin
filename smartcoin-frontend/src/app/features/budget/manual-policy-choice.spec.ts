import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConsolidationTarget } from '../../api';
import { provideLocale } from '../../core/locale';
import { ManualPolicy } from './consolidation-text';
import { ManualPolicyChoice } from './manual-policy-choice';

const target = (overrides: Partial<ConsolidationTarget>): ConsolidationTarget => ({
  entryId: 600,
  period: '2027-01',
  currentBudgetedAmount: 1800000,
  newBudgetedAmount: 210000,
  manual: false,
  changes: true,
  ...overrides,
});

/** Un anfitrión mínimo: así lo usan el diálogo de consolidación y, después, el del pago rápido. */
@Component({
  imports: [ManualPolicyChoice],
  template: `<app-manual-policy-choice [targets]="targets()" currency="ARS" [(policy)]="policy" />`,
})
class Host {
  readonly targets = signal<ConsolidationTarget[]>([]);
  readonly policy = signal<ManualPolicy | null>(null);
}

describe('ManualPolicyChoice (HU-25)', () => {
  let fixture: ComponentFixture<Host>;

  async function render(targets: ConsolidationTarget[]) {
    await TestBed.configureTestingModule({ imports: [Host], providers: [provideLocale()] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.targets.set(targets);
    await settle();
  }
  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }
  const root = () => fixture.nativeElement as HTMLElement;
  const byId = (id: string) => root().querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const radio = (id: string) => byId(id)!.querySelector<HTMLInputElement>('input')!;
  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  // El ejemplo del Resumen Visa (RN-26): diciembre editada en 300.000,00; la estimación es 210.000,00.
  const visa = [
    target({ entryId: 1, period: '2026-12', currentBudgetedAmount: 300000, manual: true }),
    target({ entryId: 2, period: '2027-01', currentBudgetedAmount: 180000 }),
    target({ entryId: 3, period: '2027-02', currentBudgetedAmount: 180000 }),
  ];

  it('lista solo las editadas con mes, monto actual y monto si se pisa', async () => {
    await render(visa);

    expect(root().querySelectorAll('[data-testid="edited-target"]')).toHaveLength(1);
    expect(text(byId('edited-target'))).toBe('diciembre 2026$ 300.000,00 $ 210.000,00');
  });

  it('no trae ninguna opción elegida y explica cada una con los recuentos', async () => {
    await render(visa);

    expect(radio('policy-keep').checked).toBe(false);
    expect(radio('policy-overwrite').checked).toBe(false);
    expect(text(byId('policy-keep-text'))).toContain('conserva su monto y sigue editada. 2 otras partidas cambian');
    expect(text(byId('policy-overwrite-text'))).toContain('toma la nueva estimación y deja de estar editada');
    expect(text(byId('policy-hint'))).toBe('Elegí una opción para poder consolidar.');
  });

  it('la elección sale por `policy`, es una sola y reemplaza a la anterior', async () => {
    await render(visa);

    radio('policy-keep').click();
    await settle();
    expect(fixture.componentInstance.policy()).toBe('KEEP');
    expect(byId('policy-hint')).toBeNull();

    radio('policy-overwrite').click();
    await settle();
    expect(fixture.componentInstance.policy()).toBe('OVERWRITE');
    expect(radio('policy-keep').checked).toBe(false);
  });

  it('muestra la elección que le pasa quien lo usa', async () => {
    await render(visa);

    fixture.componentInstance.policy.set('OVERWRITE');
    await settle();

    expect(radio('policy-overwrite').checked).toBe(true);
  });
});
