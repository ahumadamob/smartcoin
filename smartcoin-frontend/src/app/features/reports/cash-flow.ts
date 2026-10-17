import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Subscription } from 'rxjs';
import { CashFlow, ReportesService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { CURRENCIES, CURRENCY_LABELS } from '../accounts/account-labels';
import { CashFlowTable } from './cash-flow-table';

type CashFlowCurrency = 'ARS' | 'USD';

/**
 * Flujo de caja por fecha (HU-34, RN-45, D-46): cuándo entra y sale la plata en un rango de fechas, de una sola
 * moneda. Sin fechas, el backend devuelve el mes actual y la pantalla muestra el rango que resolvió. Cambiar la
 * moneda vuelve a pedir el flujo; las fechas, con «Ver». No calcula nada: las reglas y los saldos los decide el backend.
 */
@Component({
  selector: 'app-cash-flow',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CashFlowTable,
    DatePipe,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    ReactiveFormsModule,
  ],
  templateUrl: './cash-flow.html',
  styleUrl: './cash-flow.scss',
})
export class CashFlowPage implements OnInit {
  private readonly reports = inject(ReportesService);
  private readonly destroyRef = inject(DestroyRef);
  private pending: Subscription | null = null;

  protected readonly dateFormat = DATE_FORMAT;
  protected readonly currencies = CURRENCIES;
  protected readonly currencyLabels = CURRENCY_LABELS;

  protected readonly flow = signal<CashFlow | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly filters = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    currency: new FormControl<CashFlowCurrency>('ARS', { nonNullable: true }),
  });

  ngOnInit(): void {
    this.filters.controls.currency.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.search());
    this.search();
  }

  /** Pide el flujo con los filtros de la pantalla; un pedido nuevo cancela el anterior. */
  protected search(): void {
    const { from, to, currency } = this.filters.getRawValue();
    this.pending?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.pending = this.reports.getCashFlow(currency, from || undefined, to || undefined).subscribe({
      next: (flow) => {
        this.flow.set(flow);
        // La pantalla muestra el rango que resolvió el backend cuando se pidió sin una fecha. Lo que el usuario escribió
        // mientras el pedido volaba no se pisa.
        const { controls } = this.filters;
        if (!from && controls.from.value === '') {
          controls.from.setValue(flow.from, { emitEvent: false });
        }
        if (!to && controls.to.value === '') {
          controls.to.setValue(flow.to, { emitEvent: false });
        }
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.flow.set(null);
        this.error.set(messageFor(e));
        this.loading.set(false);
      },
    });
  }
}
