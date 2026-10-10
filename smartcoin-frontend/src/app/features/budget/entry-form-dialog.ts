import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  AccountResponse,
  CategorasService,
  CategoryResponse,
  CuentasService,
  EntryUpdateRequest,
  OneOffEntryRequest,
  PartidasService,
  PeriodEntry,
} from '../../api';
import { messageFor } from '../../core/error-messages';
import { formatAmountInput, parseAmount } from '../../shared/amount';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { CURRENCY_LABELS } from '../accounts/account-labels';
import { KIND_LABELS, KINDS } from '../budget-items/budget-item-labels';

/** Qué abre el diálogo: una partida nueva del período, o la edición de una que no viene de un Concepto. */
export interface EntryFormDialogData {
  /** `YYYY-MM` del mes que se está viendo. */
  period: string;
  /** Vencimiento con el que arranca un alta (`YYYY-MM-DD`), dentro del período. */
  suggestedDueDate: string;
  /** La partida que se edita; sin ella, es un alta. */
  entry?: PeriodEntry;
}

/**
 * Cómo se cierra: con la partida guardada (`created` distingue alta de edición), con `stale` si el backend dijo que el
 * período se cerró o la partida se consolidó mientras estaba abierto (la pantalla estaba desactualizada), o sin valor
 * si el usuario canceló.
 */
export type EntryFormDialogResult = { entry: PeriodEntry; created: boolean } | { stale: true };

/** Estos dos códigos significan que lo que muestra la pantalla ya no es lo que hay en el backend. */
const STALE_CODES = ['PERIOD_CLOSED', 'ENTRY_NOT_PENDING'];

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

function notBlank(control: AbstractControl): ValidationErrors | null {
  return typeof control.value === 'string' && control.value.trim() === '' ? { blank: true } : null;
}

/** Monto con coma decimal y sin signo: el presupuestado no puede ser negativo. */
function amountFormat(control: AbstractControl): ValidationErrors | null {
  const text = (control.value as string).trim();
  return text === '' || (parseAmount(text) !== null && !text.startsWith('-')) ? null : { amount: true };
}

/** Fecha `YYYY-MM-DD` que existe en el calendario. Solo el formato: el rango lo decide el backend (RN-19). */
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

type FieldName = 'name' | 'kind' | 'accountId' | 'categoryId' | 'dueDate' | 'budgetedAmount';

/**
 * Diálogo para agregar una partida puntual (HU-16, RN-19) o editar una partida sin Concepto (RN-18). Solo valida el
 * formato; las reglas (rango del vencimiento, cuenta y categoría que no existen, período cerrado, partida
 * consolidada) las decide el backend y acá se muestra su error sin perder lo cargado. El monto se convierte de coma
 * decimal a número antes de enviarlo; el frontend no calcula nada con él.
 *
 * Se carga la lista de cuentas y categorías al abrirse. En una edición el tipo queda deshabilitado: no se edita.
 */
@Component({
  selector: 'app-entry-form-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    PeriodPipe,
    RouterLink,
  ],
  templateUrl: './entry-form-dialog.html',
  styleUrl: './entry-form-dialog.scss',
})
export class EntryFormDialog implements OnInit {
  private readonly api = inject(PartidasService);
  private readonly accountsApi = inject(CuentasService);
  private readonly categoriesApi = inject(CategorasService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly ref = inject<MatDialogRef<EntryFormDialog, EntryFormDialogResult>>(MatDialogRef);
  protected readonly data = inject<EntryFormDialogData>(MAT_DIALOG_DATA);

  protected readonly entry = this.data.entry ?? null;
  protected readonly editing = this.entry !== null;
  protected readonly kinds = KINDS;
  protected readonly kindLabels = KIND_LABELS;
  protected readonly currencyLabels = CURRENCY_LABELS;

  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly categories = signal<CategoryResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** Error del backend que no es de un campo, o `null`. Los de un campo salen debajo de él. */
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  private stale = false;

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, notBlank, Validators.maxLength(100)],
    }),
    kind: new FormControl<OneOffEntryRequest.KindEnum | null>(null, Validators.required),
    accountId: new FormControl<number | null>(null, Validators.required),
    categoryId: new FormControl<number | null>(null),
    dueDate: new FormControl('', { nonNullable: true, validators: [Validators.required, isoDate] }),
    budgetedAmount: new FormControl('', { nonNullable: true, validators: [Validators.required, amountFormat] }),
  });

  private readonly selectedAccountId = toSignal(this.form.controls.accountId.valueChanges, { initialValue: null });

  /** La moneda de la cuenta elegida, para mostrarla junto al campo. */
  protected readonly selectedCurrency = computed(() => {
    const id = this.selectedAccountId();
    return this.accounts().find((account) => account.id === id)?.currency ?? null;
  });

  /**
   * La pista del campo Cuenta. Al editar, elegir una cuenta de otra moneda cambia la moneda de la partida y se avisa
   * (D-30). Se arma acá porque `mat-hint` solo se proyecta si es hijo directo del campo, no dentro de un `@if`.
   */
  protected readonly accountHint = computed(() => {
    const currency = this.selectedCurrency();
    if (this.entry && currency !== null && currency !== this.entry.currency) {
      return { text: `Esta cuenta es en ${CURRENCY_LABELS[currency]}: la partida pasa a esa moneda.`, warning: true };
    }
    return { text: 'La moneda de la partida es la de su cuenta.', warning: false };
  });

  /** El formulario se puede enviar cuando terminó de cargar y hay al menos una cuenta. */
  protected readonly ready = computed(
    () => !this.loading() && this.loadError() === null && this.accounts().length > 0,
  );

  ngOnInit(): void {
    if (this.entry) {
      this.form.setValue({
        name: this.entry.name,
        kind: this.entry.kind,
        accountId: this.entry.accountId,
        categoryId: this.entry.categoryId ?? null,
        dueDate: this.entry.dueDate,
        budgetedAmount: formatAmountInput(this.entry.budgetedAmount),
      });
      this.form.controls.kind.disable();
    } else {
      this.form.controls.dueDate.setValue(this.data.suggestedDueDate);
    }
    forkJoin({ accounts: this.accountsApi.listAccounts(), categories: this.categoriesApi.listCategories() }).subscribe({
      next: ({ accounts, categories }) => {
        this.accounts.set(accounts.accounts);
        this.categories.set(categories);
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
    const budgetedAmount = parseAmount(value.budgetedAmount);
    if (budgetedAmount === null || value.kind === null || value.accountId === null) {
      return;
    }
    this.submitting.set(true);
    if (this.entry) {
      this.update(this.entry, {
        name: value.name.trim(),
        accountId: value.accountId,
        // `null` no distingue «no enviado» de «vaciar»: vaciar se pide aparte.
        ...(value.categoryId === null
          ? { clearCategory: this.entry.categoryId !== null }
          : { categoryId: value.categoryId }),
        dueDate: value.dueDate,
        budgetedAmount,
      });
    } else {
      this.create({
        name: value.name.trim(),
        kind: value.kind,
        accountId: value.accountId,
        categoryId: value.categoryId,
        dueDate: value.dueDate,
        budgetedAmount,
      });
    }
  }

  protected cancel(): void {
    this.ref.close(this.stale ? { stale: true } : undefined);
  }

  private create(request: OneOffEntryRequest): void {
    this.api.createOneOffEntry(this.data.period, request).subscribe({
      next: (entry) => this.ref.close({ entry, created: true }),
      error: (e: unknown) => this.fail(e),
    });
  }

  private update(entry: PeriodEntry, request: EntryUpdateRequest): void {
    this.api.updateEntry(entry.id, request).subscribe({
      next: (updated) => this.ref.close({ entry: updated, created: false }),
      error: (e: unknown) => this.fail(e),
    });
  }

  /** El error de un campo sale debajo de él; cualquier otro, arriba del formulario. Lo cargado no se toca. */
  private fail(e: unknown): void {
    this.submitting.set(false);
    const body = e instanceof HttpErrorResponse && typeof e.error === 'object' ? e.error : null;
    if (typeof body?.code === 'string' && STALE_CODES.includes(body.code)) {
      this.stale = true;
    }
    const message = messageFor(e);
    const field = body?.code === 'VALIDATION_ERROR' ? (body.errors?.[0]?.field as string | undefined) : undefined;
    const control = field !== undefined ? this.form.controls[field as FieldName] : undefined;
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
