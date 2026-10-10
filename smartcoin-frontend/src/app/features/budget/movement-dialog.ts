import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, OnInit, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { AccountResponse, CuentasService, Movement, MovementRegistered, MovimientosService, PeriodEntry } from '../../api';
import { detailFor, messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { parseAmount, formatAmountInput } from '../../shared/amount';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { coveredNotice, movementWord, registerLabel } from './movement-text';
import { DatePipe } from '@angular/common';

/** Qué abre el diálogo: la partida (la fila de la vista del mes) y el mes que se está viendo. */
export interface MovementDialogData {
  entry: PeriodEntry;
  /** `YYYY-MM` del mes que se está viendo. */
  period: string;
  /** Hoy en el navegador, solo para la fecha con la que arranca el campo; el backend decide qué fecha vale. */
  today?: Date;
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

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** `YYYY-MM-DD` con la fecha local del navegador. */
function isoOf(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

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
 * El monto se convierte de coma decimal a número antes de enviarlo; el frontend no suma ni resta nada con él. Con
 * el pendiente en 0, un aviso dice que la partida está cubierta (criterio 6): no hay botón porque consolidar llega
 * con HU-23.
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
  protected readonly data = inject<MovementDialogData>(MAT_DIALOG_DATA);

  protected readonly entry = this.data.entry;
  protected readonly dateFormat = DATE_FORMAT;
  protected readonly title = registerLabel(this.entry.kind);
  protected readonly word = movementWord(this.entry.kind);
  /**
   * El aviso de «ya está cubierta», o `null` si todavía queda pendiente. Solo compara con lo que informa el backend:
   * no calcula. Una Estimada con presupuestado 0 no tiene pendiente pero tampoco movimientos: no está «cubierta».
   */
  protected readonly notice =
    this.entry.status === 'PARTIAL' && this.entry.pendingAmount === 0
      ? coveredNotice(this.entry.name, this.entry.pendingAmount, this.entry.currency, (amount, currency) =>
          this.money.transform(amount, currency),
        )
      : null;

  /** Solo las cuentas de la moneda de la partida (S-02). */
  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly movements = signal<Movement[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** Error del backend que no es de un campo, o `null`. Los de un campo salen debajo de él. */
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  private stale = false;

  protected readonly form = new FormGroup({
    date: new FormControl('', { nonNullable: true, validators: [Validators.required, isoDate] }),
    amount: new FormControl('', { nonNullable: true, validators: [Validators.required, positiveAmount] }),
    accountId: new FormControl<number | null>(null, Validators.required),
    note: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
  });

  /** El formulario se puede enviar cuando terminó de cargar y hay al menos una cuenta de esa moneda. */
  protected readonly ready = computed(
    () => !this.loading() && this.loadError() === null && this.accounts().length > 0,
  );

  ngOnInit(): void {
    this.form.controls.date.setValue(isoOf(this.data.today ?? new Date()));
    // El pendiente sugiere el monto de un pago que cierra la partida; si ya no queda nada, el campo arranca vacío.
    if (this.entry.pendingAmount > 0) {
      this.form.controls.amount.setValue(formatAmountInput(this.entry.pendingAmount));
    }
    this.form.controls.accountId.setValue(this.entry.accountId);
    forkJoin({
      accounts: this.accountsApi.listAccounts(),
      movements: this.api.listMovements(this.entry.id),
    }).subscribe({
      next: ({ accounts, movements }) => {
        this.accounts.set(accounts.accounts.filter((account) => account.currency === this.entry.currency));
        this.movements.set(movements);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.loadError.set(messageFor(e));
        this.loading.set(false);
      },
    });
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
    const note = value.note.trim();
    this.api
      .registerMovement(this.entry.id, {
        date: value.date,
        amount,
        accountId: value.accountId,
        ...(note === '' ? {} : { note }),
      })
      .subscribe({
        next: (registered) => this.ref.close(registered),
        error: (e: unknown) => this.fail(e),
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
