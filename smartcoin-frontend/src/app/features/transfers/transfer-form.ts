import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { AccountResponse, Transfer, TransferenciasService } from '../../api';
import { detailFor, messageFor } from '../../core/error-messages';
import { parseAmount } from '../../shared/amount';
import { isoDate } from '../budget/iso-date';
import { targetAccounts } from './transfer-targets';


function amountFormat(control: AbstractControl): ValidationErrors | null {
  const text = control.value as string;
  return text === '' || parseAmount(text) !== null ? null : { amount: true };
}

function positiveAmount(control: AbstractControl): ValidationErrors | null {
  const value = parseAmount(control.value as string);
  return value !== null && value <= 0 ? { positive: true } : null;
}

/** Hoy en el navegador, `YYYY-MM-DD`: solo una sugerencia; la fecha la valida el backend (RN-36). */
export function todayIso(now = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * Alta de una transferencia entre cuentas de la misma moneda (HU-27). Un solo monto, con coma decimal: la API
 * recibe los dos iguales. Solo valida formato; las reglas (RN-36) las decide el backend y su error se muestra sin
 * perder lo cargado.
 */
@Component({
  selector: 'app-transfer-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  templateUrl: './transfer-form.html',
  styleUrl: './transfer-form.scss',
})
export class TransferForm {
  private readonly api = inject(TransferenciasService);
  private readonly destroyRef = inject(DestroyRef);

  /** Las cuentas del usuario. */
  readonly accounts = input.required<AccountResponse[]>();

  /** La transferencia se guardó. */
  readonly saved = output<Transfer>();
  readonly cancelled = output<void>();

  protected readonly form = new FormGroup({
    sourceAccountId: new FormControl<number | null>(null, Validators.required),
    targetAccountId: new FormControl<number | null>(null, Validators.required),
    date: new FormControl(todayIso(), { nonNullable: true, validators: [Validators.required, isoDate] }),
    amount: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, amountFormat, positiveAmount],
    }),
    note: new FormControl('', { nonNullable: true, validators: Validators.maxLength(200) }),
  });
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  private readonly sourceId = signal<number | null>(null);

  protected readonly targets = computed(() => targetAccounts(this.accounts(), this.sourceId()));

  constructor() {
    this.form.controls.sourceAccountId.valueChanges.pipe(takeUntilDestroyed()).subscribe((id) => {
      this.sourceId.set(id);
      const target = this.form.controls.targetAccountId;
      if (target.value !== null && !this.targets().some((account) => account.id === target.value)) {
        target.setValue(null);
      }
    });
  }

  protected label(account: AccountResponse): string {
    return `${account.name} (${account.currency})`;
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const amount = parseAmount(raw.amount)!;
    const note = raw.note.trim();
    this.error.set(null);
    this.submitting.set(true);
    this.api
      .createTransfer({
        sourceAccountId: raw.sourceAccountId!,
        targetAccountId: raw.targetAccountId!,
        date: raw.date,
        sourceAmount: amount,
        targetAmount: amount,
        ...(note === '' ? {} : { note }),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (transfer) => {
          this.submitting.set(false);
          this.form.controls.amount.reset('');
          this.form.controls.note.reset('');
          this.form.controls.amount.markAsUntouched();
          this.saved.emit(transfer);
        },
        error: (e: unknown) => {
          this.submitting.set(false);
          // El `detail` nombra la cuenta o el mes que falló: dice más que el texto fijo del código.
          this.error.set(isVague(e) ? detailFor(e) : messageFor(e));
        },
      });
  }
}

/** Los errores de fecha traen en `detail` la causa exacta (qué cuenta, qué mes). */
function isVague(error: unknown): boolean {
  const code = (error as { error?: { code?: string } })?.error?.code;
  return code === 'DATE_OUT_OF_RANGE' || code === 'PERIOD_CLOSED';
}
