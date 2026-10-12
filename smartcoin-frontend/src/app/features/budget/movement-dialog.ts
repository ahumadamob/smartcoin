import { DatePipe, formatDate } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  LOCALE_ID,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  FormGroupDirective,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { catchError, EMPTY, forkJoin, Observable, switchMap } from 'rxjs';
import {
  AccountResponse,
  CuentasService,
  Movement,
  MovementDates,
  MovementRegistered,
  MovimientosService,
  PeriodEntry,
} from '../../api';
import { detailFor, messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { parseAmount, formatAmountInput } from '../../shared/amount';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { canChangeMovements } from './entry-actions';
import { MovementDeleteDialog, MovementDeleteDialogData, MovementDeleteDialogResult } from './movement-delete-dialog';
import {
  correctedMessage,
  correctLabel,
  coveredNotice,
  deletedMessage,
  movementWord,
  notOpenYetNotice,
  registerLabel,
  suggestedDate,
  windowNotice,
  windowNotOpenYet,
} from './movement-text';

/** Qué abre el diálogo: la partida (la fila de la vista del mes) y el mes que se está viendo. */
export interface MovementDialogData {
  entry: PeriodEntry;
  /** `YYYY-MM` del mes que se está viendo. */
  period: string;
  /** El período está cerrado (RN-09): no se corrigen ni se eliminan movimientos. */
  readonly: boolean;
  /**
   * Vuelve a pedir el mes (la pantalla de atrás queda con los valores del backend) y devuelve esta partida ya
   * actualizada, o `null` si ya no está. Es lo que se usa después de corregir o eliminar un movimiento (HU-21).
   */
  refresh: () => Observable<PeriodEntry | null>;
}

/**
 * Cómo se cierra: con lo que respondió el backend (el movimiento y la partida ya actualizada), con `stale` si el
 * backend dijo que el período se cerró o la partida ya no está pendiente (la pantalla estaba desactualizada), o sin
 * valor si el usuario canceló.
 */
export type MovementDialogResult = MovementRegistered | { stale: true };

/** Estos códigos significan que lo que muestra la pantalla ya no es lo que hay en el backend. */
const STALE_CODES = ['PERIOD_CLOSED', 'ENTRY_NOT_PENDING', 'NOT_FOUND'];

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Monto con coma decimal y mayor que 0. El formato lo valida el frontend; el resto, el backend. */
function positiveAmount(control: AbstractControl): ValidationErrors | null {
  const text = (control.value as string).trim();
  if (text === '') {
    return null;
  }
  const value = parseAmount(text);
  if (value === null || text.startsWith('-')) {
    return { amount: true };
  }
  return value > 0 ? null : { positive: true };
}

/** Fecha `YYYY-MM-DD` que existe en el calendario. Solo el formato: el rango lo decide el backend (RN-21). */
function isoDate(control: AbstractControl): ValidationErrors | null {
  const text = control.value as string;
  if (text === '') {
    return null;
  }
  const match = ISO_DATE.exec(text);
  if (match) {
    const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
    const date = new Date(year, month - 1, day);
    if (date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day) {
      return null;
    }
  }
  return { date: true };
}

type FieldName = 'date' | 'amount' | 'accountId' | 'note';

/**
 * Diálogo para registrar un cobro o pago de una partida (HU-19, RN-21, RN-23). Muestra el presupuestado, el real y el
 * pendiente tal como los informa el backend, los movimientos ya registrados y el formulario. Solo valida el formato;
 * las reglas (ventana de anticipación, apertura de la cuenta, fecha futura, mes cerrado, moneda, partida
 * consolidada) las decide el backend y acá se muestra su error sin perder lo cargado.
 *
 * La fecha con la que arranca el campo, la fecha más temprana y la ventana de anticipación las informa el backend
 * (`GET /api/entries/{id}/movement-dates`, HU-20), y se vuelve a pedir al cambiar de cuenta porque la apertura de la
 * cuenta puede mover la fecha más temprana. Acá no se calcula ni se repite la regla.
 *
 * El monto se convierte de coma decimal a número antes de enviarlo; el frontend no suma ni resta nada con él. Con
 * el pendiente en 0, un aviso dice que la partida está cubierta (criterio 6): no hay botón porque consolidar llega
 * con HU-23.
 *
 * HU-21: cada movimiento de la lista ofrece «Editar» y «Eliminar» mientras la partida esté pendiente y el período
 * abierto. Editar carga el formulario del alta con los valores del movimiento y lo envía con `PUT`; eliminar pide
 * confirmación con fecha, monto y cuenta. El diálogo sigue abierto: después de cada cambio vuelve a pedir la lista y
 * la partida (los valores salen del backend) y la vista del mes de atrás se actualiza.
 */
@Component({
  selector: 'app-movement-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MoneyPipe,
    PeriodPipe,
    RouterLink,
  ],
  templateUrl: './movement-dialog.html',
  styleUrl: './movement-dialog.scss',
})
export class MovementDialog implements OnInit {
  private readonly api = inject(MovimientosService);
  private readonly accountsApi = inject(CuentasService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly ref = inject<MatDialogRef<MovementDialog, MovementDialogResult>>(MatDialogRef);
  private readonly money = new MoneyPipe();
  private readonly periodPipe = new PeriodPipe();
  private readonly locale = inject(LOCALE_ID);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);
  protected readonly data = inject<MovementDialogData>(MAT_DIALOG_DATA);

  /** La partida con los valores que informó el backend por última vez (cambia al corregir o eliminar). */
  protected readonly entry = signal(this.data.entry);
  protected readonly dateFormat = DATE_FORMAT;
  protected readonly word = movementWord(this.data.entry.kind);
  /** El movimiento que se está corrigiendo, o `null` si el formulario es del alta. */
  protected readonly editing = signal<Movement | null>(null);
  protected readonly title = computed(() =>
    this.editing() === null ? registerLabel(this.entry().kind) : correctLabel(this.entry().kind),
  );
  protected readonly submitLabel = computed(() =>
    this.editing() === null ? registerLabel(this.entry().kind) : 'Guardar cambios',
  );
  /** «Editar» y «Eliminar» por fila: solo con la partida pendiente y el período abierto. */
  protected readonly canChange = computed(() => canChangeMovements(this.entry(), this.data.readonly));
  /** Qué se hizo con un movimiento (corregido, eliminado), o `null`. */
  protected readonly changeNotice = signal<string | null>(null);
  /**
   * El aviso de «ya está cubierta», o `null` si todavía queda pendiente. Solo compara con lo que informa el backend:
   * no calcula. Una Estimada con presupuestado 0 no tiene pendiente pero tampoco movimientos: no está «cubierta».
   */
  protected readonly notice = computed(() => {
    const entry = this.entry();
    return entry.status === 'PARTIAL' && entry.pendingAmount === 0
      ? coveredNotice(entry.name, entry.pendingAmount, entry.currency, (amount, currency) =>
          this.money.transform(amount, currency),
        )
      : null;
  });

  /** Solo las cuentas de la moneda de la partida (S-02). */
  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly movements = signal<Movement[]>([]);
  /** Rango de fechas que admite la partida con la cuenta elegida, según el backend. `null` hasta que carga. */
  protected readonly dates = signal<MovementDates | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** Error del backend que no es de un campo, o `null`. Los de un campo salen debajo de él. */
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  private stale = false;

  /** La directiva guarda si el formulario «ya se envió»: hay que limpiarla para que no marque errores al volver a cargarlo. */
  private readonly formDirective = viewChild(FormGroupDirective);

  protected readonly form = new FormGroup({
    date: new FormControl('', { nonNullable: true, validators: [Validators.required, isoDate] }),
    amount: new FormControl('', { nonNullable: true, validators: [Validators.required, positiveAmount] }),
    accountId: new FormControl<number | null>(null, Validators.required),
    note: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
  });

  /** Una línea con la ventana de anticipación y la fecha más temprana, o `null` si no hay que mostrarla. */
  protected readonly windowText = computed(() => {
    const dates = this.dates();
    if (dates === null) {
      return null;
    }
    const earliest = formatDate(dates.earliestDate, DATE_FORMAT, this.locale);
    return windowNotOpenYet(dates)
      ? notOpenYetNotice(earliest)
      : windowNotice(dates.earlyDays, this.periodPipe.transform(this.data.period), earliest);
  });

  /** `false` cuando la ventana de la partida todavía no abrió: ninguna fecha sirve y no se deja enviar. */
  protected readonly windowOpen = computed(() => {
    const dates = this.dates();
    return dates === null || !windowNotOpenYet(dates);
  });

  /**
   * El formulario se puede enviar cuando terminó de cargar, hay una cuenta de esa moneda y la ventana abrió. Al
   * corregir no hace falta que la ventana de la partida esté abierta: un movimiento que ya existe se puede corregir.
   */
  protected readonly ready = computed(
    () =>
      !this.loading() &&
      this.loadError() === null &&
      this.accounts().length > 0 &&
      (this.editing() !== null || this.windowOpen()),
  );

  ngOnInit(): void {
    // El pendiente sugiere el monto de un pago que cierra la partida; si ya no queda nada, el campo arranca vacío.
    const entry = this.entry();
    if (entry.pendingAmount > 0) {
      this.form.controls.amount.setValue(formatAmountInput(entry.pendingAmount));
    }
    this.form.controls.accountId.setValue(entry.accountId);
    forkJoin({
      accounts: this.accountsApi.listAccounts(),
      movements: this.api.listMovements(entry.id),
      dates: this.api.getMovementDates(entry.id, entry.accountId),
    }).subscribe({
      next: ({ accounts, movements, dates }) => {
        this.accounts.set(accounts.accounts.filter((account) => account.currency === entry.currency));
        this.movements.set(movements);
        this.dates.set(dates);
        this.form.controls.date.setValue(suggestedDate(dates));
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.loadError.set(messageFor(e));
        this.loading.set(false);
      },
    });
    // La fecha más temprana depende de la cuenta (su apertura): al cambiarla, se vuelve a pedir al backend. La fecha
    // que ya escribió el usuario no se toca; si falla el pedido, queda el rango anterior y el backend decide al enviar.
    this.form.controls.accountId.valueChanges
      .pipe(
        switchMap((accountId) =>
          accountId === null
            ? EMPTY
            : this.api.getMovementDates(this.entry().id, accountId).pipe(catchError(() => EMPTY)),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((dates) => this.dates.set(dates));
  }

  protected submit(): void {
    if (this.submitting() || !this.ready()) {
      return;
    }
    this.error.set(null);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    const value = this.form.getRawValue();
    const amount = parseAmount(value.amount);
    if (amount === null || value.accountId === null) {
      return;
    }
    this.submitting.set(true);
    this.changeNotice.set(null);
    const note = value.note.trim();
    const request = {
      date: value.date,
      amount,
      accountId: value.accountId,
      ...(note === '' ? {} : { note }),
    };
    const editing = this.editing();
    if (editing !== null) {
      this.api.updateMovement(editing.id, request).subscribe({
        next: (updated) => {
          this.submitting.set(false);
          this.editing.set(null);
          this.changeNotice.set(
            correctedMessage(updated.entry.kind, updated.movement.amount, updated.movement.currency, (a, c) =>
              this.money.transform(a, c),
            ),
          );
          this.refreshAfterChange(() => this.focusRowAction(updated.movement.id));
        },
        error: (e: unknown) => this.fail(e),
      });
      return;
    }
    this.api.registerMovement(this.entry().id, request).subscribe({
      next: (registered) => this.ref.close(registered),
      error: (e: unknown) => this.fail(e),
    });
  }

  /** «Editar»: el formulario del alta con los valores del movimiento. El backend decide si se puede. */
  protected startEdit(movement: Movement): void {
    this.error.set(null);
    this.changeNotice.set(null);
    this.editing.set(movement);
    this.loadForm({
      date: movement.date,
      amount: formatAmountInput(movement.amount),
      accountId: movement.accountId,
      note: movement.note ?? '',
    });
    this.focusAfterRender('[formcontrolname="date"]');
  }

  /** Vuelve al formulario del alta, con lo que sugiere para un movimiento nuevo. El foco vuelve a la fila. */
  protected cancelEdit(): void {
    const editing = this.editing();
    this.editing.set(null);
    this.error.set(null);
    this.resetRegisterForm();
    if (editing !== null) {
      this.focusRowAction(editing.id);
    }
  }

  /** «Eliminar»: pide confirmar con fecha, monto y cuenta; el diálogo de confirmación hace el pedido. */
  protected remove(movement: Movement): void {
    this.error.set(null);
    const list = this.movements();
    const index = list.findIndex((candidate) => candidate.id === movement.id);
    // Si la fila desaparece, el foco va a la que ocupa su lugar, si no a la anterior, y si no queda ninguna, al campo.
    const candidates = [...list.slice(index + 1), ...list.slice(0, Math.max(index, 0)).reverse()].map((m) => m.id);
    this.dialog
      .open<MovementDeleteDialog, MovementDeleteDialogData, MovementDeleteDialogResult>(MovementDeleteDialog, {
        data: { movement, entryName: this.entry().name, kind: this.entry().kind, isOnly: list.length === 1 },
        width: '480px',
        maxWidth: '95vw',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (result === undefined) {
          this.focusRowAction(movement.id, 'delete-movement');
          return;
        }
        if ('stale' in result) {
          this.stale = true;
          this.refreshAfterChange(() => undefined);
          return;
        }
        if (this.editing()?.id === movement.id) {
          this.editing.set(null);
          this.resetRegisterForm();
        }
        this.changeNotice.set(
          deletedMessage(this.entry().kind, movement.amount, movement.currency, (a, c) => this.money.transform(a, c)),
        );
        this.refreshAfterChange(() => {
          const next = candidates.find((id) => this.movements().some((m) => m.id === id));
          if (next !== undefined) {
            this.focusRowAction(next);
          } else {
            this.focusAfterRender('[formcontrolname="date"]');
          }
        });
      });
  }

  /**
   * Vuelve a pedir la lista y la partida al backend (por la vista del mes, que además queda actualizada atrás). Si la
   * partida ya no está o dejó de ser pendiente, las acciones desaparecen solas; `onDone` coloca el foco.
   */
  private refreshAfterChange(onDone: () => void): void {
    const entryId = this.entry().id;
    forkJoin({
      entry: this.data.refresh(),
      movements: this.api.listMovements(entryId),
      dates: this.api.getMovementDates(entryId, this.entry().accountId),
    }).subscribe({
      next: ({ entry, movements, dates }) => {
        this.movements.set(movements);
        if (entry === null) {
          this.stale = true;
          this.ref.close({ stale: true });
          return;
        }
        this.entry.set(entry);
        if (this.editing() === null) {
          this.dates.set(dates);
          this.resetRegisterForm(dates);
        }
        afterNextRender(onDone, { injector: this.injector });
      },
      error: (e: unknown) => this.error.set(messageFor(e)),
    });
  }

  /** El formulario del alta: hoy, la cuenta de la partida y, si queda pendiente, ese monto. */
  private resetRegisterForm(dates: MovementDates | null = this.dates()): void {
    const entry = this.entry();
    this.loadForm({
      date: dates === null ? '' : suggestedDate(dates),
      amount: entry.pendingAmount > 0 ? formatAmountInput(entry.pendingAmount) : '',
      accountId: entry.accountId,
      note: '',
    });
  }

  /** Carga valores nuevos en el formulario como si fuera uno recién abierto: sin errores ni «enviado» de antes. */
  private loadForm(values: { date: string; amount: string; accountId: number | null; note: string }): void {
    const directive = this.formDirective();
    if (directive) {
      directive.resetForm(values);
    } else {
      this.form.reset(values);
    }
  }

  private focusRowAction(movementId: number, action: 'edit-movement' | 'delete-movement' = 'edit-movement'): void {
    this.focusAfterRender(`[data-movement-id="${movementId}"] [data-testid="${action}"]`);
  }

  private focusAfterRender(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  protected cancel(): void {
    this.ref.close(this.stale ? { stale: true } : undefined);
  }

  /**
   * El error de un campo sale debajo de él; cualquier otro, arriba del formulario. Lo cargado no se toca. Un 409 de
   * fecha se muestra con el `detail` del backend, que nombra la fecha límite o la cuenta.
   */
  private fail(e: unknown): void {
    this.submitting.set(false);
    const body = e instanceof HttpErrorResponse && typeof e.error === 'object' ? e.error : null;
    const code = typeof body?.code === 'string' ? (body.code as string) : null;
    if (code !== null && STALE_CODES.includes(code)) {
      this.stale = true;
      if (this.editing() !== null) {
        // Lo que se ve ya no es lo que hay: se vuelve a pedir, y las acciones desaparecen si ya no corresponden.
        this.refreshAfterChange(() => undefined);
      }
    }
    let field: FieldName | undefined;
    if (code === 'VALIDATION_ERROR') {
      field = body?.errors?.[0]?.field as FieldName | undefined;
    } else if (code === 'DATE_OUT_OF_RANGE') {
      field = 'date';
    } else if (code === 'CURRENCY_MISMATCH') {
      field = 'accountId';
    }
    const message = code === 'DATE_OUT_OF_RANGE' || code === 'PERIOD_CLOSED' || code === 'ENTRY_NOT_PENDING' ? detailFor(e) : messageFor(e);
    const control = field !== undefined ? this.form.controls[field] : undefined;
    if (control) {
      control.setErrors({ server: message });
      control.markAsTouched();
      // El foco va al campo con el error: queda a la vista aunque el diálogo tenga scroll, y se anuncia.
      this.host.nativeElement.querySelector<HTMLElement>(`[formcontrolname="${field}"]`)?.focus();
    } else {
      this.error.set(message);
    }
  }
}
