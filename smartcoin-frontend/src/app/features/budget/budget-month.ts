import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  Injector,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, EMPTY, map, switchMap, tap } from 'rxjs';
import { PeriodEntry, PeriodView, PerodosService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { EntryDeleteDialog, EntryDeleteDialogData, EntryDeleteDialogResult } from './entry-delete-dialog';
import { EntryFormDialog, EntryFormDialogData, EntryFormDialogResult } from './entry-form-dialog';
import { EntryTable } from './entry-table';
import { isPeriod, nextPeriod, PeriodRange, periodsInRange, previousPeriod } from './period-nav';
import { suggestedDueDate } from './suggested-due-date';

/** Por qué no se muestra un mes: el período no existe para el usuario, o la API falló por otra causa. */
interface ViewError {
  unavailable: boolean;
  message: string;
}

/**
 * A dónde va el foco cuando la fila que lo tenía ya no existe (HU-18): la fila que ocupa su lugar, si no la anterior
 * y, si la sección quedó vacía, su encabezado.
 */
interface RemovalFocus {
  /** Ids de las partidas que quedan en la sección, de la más cercana a la más lejana. */
  candidates: number[];
  /** `aria-label` de la sección, que es su encabezado. */
  heading: string;
}

/**
 * Presupuesto del mes (HU-15), en `/presupuesto` (el período actual, que decide el backend) y `/presupuesto/:period`.
 * Solo muestra: las partidas, su orden, sus valores derivados y los totales vienen calculados (RN-44).
 *
 * Dónde van las acciones: las del período (agregar una partida, HU-16; cerrar el mes, HU-30), en la cabecera, junto
 * al título; las de cada partida, en la última columna de `EntryTable`. Las dos dependen de `readonly`: un período
 * cerrado se ve igual pero sin acciones. Después de guardar o eliminar, la vista se vuelve a pedir al backend: acá no
 * se recalcula nada.
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
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly injector = inject(Injector);

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

  /** «Agregar partida»: abre el diálogo para una partida puntual del mes que se está viendo (HU-16). */
  protected addEntry(): void {
    const period = this.period();
    const range = this.range();
    if (period === null || range === null || !isPeriod(period) || this.readonly()) {
      return;
    }
    this.openEntryForm({ period, suggestedDueDate: suggestedDueDate(period, range.currentPeriod, new Date()) });
  }

  /** «Editar» una partida sin Concepto: el mismo diálogo, con sus datos (HU-16). */
  protected editEntry(entry: PeriodEntry): void {
    const period = this.period();
    if (period === null || !isPeriod(period) || this.readonly()) {
      return;
    }
    this.openEntryForm({ period, suggestedDueDate: entry.dueDate, entry });
  }

  /** «Editar monto» de una recurrente: el mismo diálogo, solo con el presupuestado (HU-17). */
  protected editAmount(entry: PeriodEntry): void {
    const period = this.period();
    if (period === null || !isPeriod(period) || this.readonly()) {
      return;
    }
    this.openEntryForm({ period, suggestedDueDate: entry.dueDate, entry, amountOnly: true });
  }

  /** «Eliminar» una partida: el diálogo explica qué va a pasar y, al confirmar, se vuelve a pedir el mes (HU-18). */
  protected removeEntry(entry: PeriodEntry): void {
    const period = this.period();
    if (period === null || !isPeriod(period) || this.readonly()) {
      return;
    }
    const focus = this.removalFocus(entry);
    this.dialog
      .open<EntryDeleteDialog, EntryDeleteDialogData, EntryDeleteDialogResult>(EntryDeleteDialog, {
        data: { entry, period },
        width: '560px',
        maxWidth: '95vw',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (result === undefined) {
          return;
        }
        if ('stale' in result) {
          // El período se cerró o la partida ya no existe: lo que se ve ya no es lo que hay.
          this.reload(null);
          return;
        }
        this.snackBar.open(result.message, undefined, { duration: 6000 });
        this.reload(null, focus);
      });
  }

  protected go(period: string | null): void {
    if (period !== null) {
      void this.router.navigate(['/presupuesto', period]);
    }
  }

  private openEntryForm(data: EntryFormDialogData): void {
    this.dialog
      .open<EntryFormDialog, EntryFormDialogData, EntryFormDialogResult>(EntryFormDialog, {
        data,
        width: '560px',
        maxWidth: '95vw',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (result === undefined) {
          return;
        }
        if ('stale' in result) {
          // El backend dijo que el período se cerró o la partida se consolidó: lo que se ve ya no es lo que hay.
          this.reload(null);
          return;
        }
        this.snackBar.open(
          result.created ? `Partida «${result.entry.name}» agregada.` : `Partida «${result.entry.name}» guardada.`,
          undefined,
          { duration: 4000 },
        );
        this.reload(result.entry.id);
      });
  }

  /**
   * Vuelve a pedir el mes que se está viendo, sin vaciar la pantalla: las tablas siguen montadas y el foco no se
   * pierde. Si la partida guardada cambió de lugar, el navegador suelta el foco al moverla: se le devuelve.
   */
  private reload(focusEntryId: number | null, afterRemoval: RemovalFocus | null = null): void {
    const period = this.period();
    if (period === null || !isPeriod(period)) {
      return;
    }
    this.api
      .getPeriod(period)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => {
          // Si mientras tanto se cambió de mes, esta respuesta ya no corresponde.
          if (this.period() !== view.period) {
            return;
          }
          this.view.set(view);
          this.range.set({ startPeriod: view.startPeriod, currentPeriod: view.currentPeriod, horizon: view.horizon });
          if (afterRemoval !== null) {
            afterNextRender(() => this.focusAfterRemoval(afterRemoval), { injector: this.injector });
          }
          if (focusEntryId !== null) {
            afterNextRender(
              () => {
                const lost = !document.activeElement || document.activeElement === document.body;
                if (lost) {
                  const row = `[data-entry-id="${focusEntryId}"]`;
                  document
                    .querySelector<HTMLElement>(`${row} [data-testid="edit-entry"], ${row} [data-testid="edit-amount"]`)
                    ?.focus();
                }
              },
              { injector: this.injector },
            );
          }
        },
        error: (e: unknown) => this.snackBar.open(messageFor(e), undefined, { duration: 6000 }),
      });
  }

  /** Las partidas que quedan en la sección de `entry`, de la que viene después a la más lejana (HU-18). */
  private removalFocus(entry: PeriodEntry): RemovalFocus | null {
    const view = this.view();
    if (view === null) {
      return null;
    }
    const isIncome = view.incomes.some((candidate) => candidate.id === entry.id);
    const list = (isIncome ? view.incomes : view.expenses).map((candidate) => candidate.id);
    const index = list.indexOf(entry.id);
    const following = list.slice(index + 1);
    const preceding = list.slice(0, Math.max(index, 0)).reverse();
    return { candidates: [...following, ...preceding], heading: isIncome ? 'Ingresos' : 'Gastos' };
  }

  /**
   * La fila eliminada llevaba el foco: si el navegador lo soltó, va a la acción de la fila que ocupa su lugar y,
   * si no queda ninguna con acciones, al encabezado de la sección (que lo recibe por código).
   */
  private focusAfterRemoval(target: RemovalFocus): void {
    const lost = !document.activeElement || document.activeElement === document.body;
    if (!lost) {
      return;
    }
    for (const id of target.candidates) {
      const action = document.querySelector<HTMLElement>(
        `[data-entry-id="${id}"] [data-testid="actions"] button`,
      );
      if (action) {
        action.focus();
        return;
      }
    }
    document.querySelector<HTMLElement>(`section[aria-label="${target.heading}"] h2`)?.focus();
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
