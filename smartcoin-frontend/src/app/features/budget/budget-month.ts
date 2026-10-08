import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, EMPTY, map, switchMap, tap } from 'rxjs';
import { PeriodView, PerodosService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { EntryTable } from './entry-table';
import { isPeriod, nextPeriod, PeriodRange, periodsInRange, previousPeriod } from './period-nav';

/** Por qué no se muestra un mes: el período no existe para el usuario, o la API falló por otra causa. */
interface ViewError {
  unavailable: boolean;
  message: string;
}

/**
 * Presupuesto del mes (HU-15), en `/presupuesto` (el período actual, que decide el backend) y `/presupuesto/:period`.
 * Solo muestra: las partidas, su orden, sus valores derivados y los totales vienen calculados (RN-44).
 *
 * Dónde van las acciones, que llegan desde HU-16: las del período (agregar una partida, cerrar el mes), en la
 * cabecera, junto al título; las de cada partida, en la última columna de `EntryTable`. Las dos dependen de
 * `readonly`: un período cerrado se ve igual pero sin acciones.
 */
@Component({
  selector: 'app-budget-month',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EntryTable, MatButtonModule, MatFormFieldModule, MatSelectModule, MoneyPipe, PeriodPipe, RouterLink],
  templateUrl: './budget-month.html',
  styleUrl: './budget-month.scss',
})
export class BudgetMonth implements OnInit {
  private readonly api = inject(PerodosService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly view = signal<PeriodView | null>(null);
  /** El período pedido: el de la URL o, sin él, el actual, que se conoce cuando responde el backend. */
  protected readonly period = signal<string | null>(null);
  /** Límites de la navegación, de la última respuesta. Se conservan mientras carga el mes siguiente. */
  protected readonly range = signal<PeriodRange | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ViewError | null>(null);

  protected readonly previous = computed(() => this.neighbour(previousPeriod));
  protected readonly next = computed(() => this.neighbour(nextPeriod));
  protected readonly periods = computed(() => {
    const range = this.range();
    return range ? periodsInRange(range) : [];
  });
  protected readonly isCurrent = computed(() => this.period() !== null && this.period() === this.range()?.currentPeriod);
  /** Un período cerrado no tiene acciones (RN-09). */
  protected readonly readonly = computed(() => this.view()?.status === 'CLOSED');
  protected readonly isEmpty = computed(() => {
    const view = this.view();
    return view !== null && view.incomes.length === 0 && view.expenses.length === 0;
  });

  ngOnInit(): void {
    // Cada cambio de mes cancela el pedido anterior: no puede llegar una respuesta vieja después de una nueva.
    this.route.paramMap
      .pipe(
        map((params) => params.get('period')),
        tap((period) => {
          this.period.set(period);
          this.view.set(null);
          this.error.set(null);
          this.loading.set(true);
        }),
        switchMap((period) => {
          if (period !== null && !isPeriod(period)) {
            this.fail({ unavailable: true, message: 'La dirección no corresponde a un mes válido.' });
            return EMPTY;
          }
          return (period === null ? this.api.getCurrentPeriod() : this.api.getPeriod(period)).pipe(
            catchError((e: unknown) => {
              const notFound = e instanceof HttpErrorResponse && (e.status === 404 || e.status === 400);
              this.fail(
                notFound
                  ? {
                      unavailable: true,
                      message: 'Ese mes no existe en tu presupuesto: es anterior a tu período inicial o posterior al horizonte.',
                    }
                  : { unavailable: false, message: messageFor(e) },
              );
              return EMPTY;
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((view) => {
        this.view.set(view);
        this.period.set(view.period);
        this.range.set({ startPeriod: view.startPeriod, currentPeriod: view.currentPeriod, horizon: view.horizon });
        this.loading.set(false);
      });
  }

  protected go(period: string | null): void {
    if (period !== null) {
      void this.router.navigate(['/presupuesto', period]);
    }
  }

  private neighbour(step: (period: string, range: PeriodRange) => string | null): string | null {
    const period = this.period();
    const range = this.range();
    return period !== null && range !== null && isPeriod(period) ? step(period, range) : null;
  }

  private fail(error: ViewError): void {
    this.error.set(error);
    this.loading.set(false);
  }
}
