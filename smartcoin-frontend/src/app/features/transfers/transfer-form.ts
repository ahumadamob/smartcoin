import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
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
import { formatAmountInput, parseAmount } from '../../shared/amount';
import { isoDate } from '../budget/iso-date';
import { exchangeKind } from './transfer-kind';
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
 * Alta o edición de una transferencia entre cuentas (HU-27, HU-28, HU-29). Con `transfer` carga sus valores, incluidos
 * uno o dos montos según las monedas, y guarda con `PUT`; es el mismo formulario y el backend aplica las mismas reglas. Entre cuentas de la misma moneda, un solo monto, con coma
 * decimal: la API recibe los dos iguales. Entre monedas distintas, dos: el de pesos y el de dólares, cada uno con su
 * símbolo; el tipo de cambio lo calcula el backend y se ve al guardar (D-40). Solo valida formato; las reglas (RN-36) las decide el backend y su error se muestra sin
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

  /** La transferencia que se corrige; sin ella, es un alta. */
  readonly transfer = input<Transfer | null>(null);

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
    // Monto en dólares: solo se pide, y solo cuenta para la validez, si las dos monedas son distintas (HU-28).
    usdAmount: new FormControl(
      { value: '', disabled: true },
      { nonNullable: true, validators: [Validators.required, amountFormat, positiveAmount] },
    ),
    note: new FormControl('', { nonNullable: true, validators: Validators.maxLength(200) }),
  });
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  private readonly sourceId = signal<number | null>(null);
  private readonly targetId = signal<number | null>(null);

  protected readonly targets = computed(() => targetAccounts(this.accounts(), this.sourceId()));

  /** Moneda de las dos cuentas elegidas; nulo mientras falte alguna. */
  private readonly currencies = computed(() => {
    const source = this.accounts().find((account) => account.id === this.sourceId());
    const target = this.accounts().find((account) => account.id === this.targetId());
    return source && target ? { source: source.currency, target: target.currency } : null;
  });

  /** Con monedas distintas se piden dos montos, el de pesos y el de dólares (HU-28). */
  protected readonly crossCurrency = computed(() => {
    const currencies = this.currencies();
    return currencies !== null && currencies.source !== currencies.target;
  });
  protected readonly kind = computed(() => {
    const currencies = this.currencies();
    return currencies ? exchangeKind(currencies.source, currencies.target) : null;
  });

  constructor() {
    effect(() => {
      const transfer = this.transfer();
      if (transfer !== null) {
        untracked(() => this.load(transfer));
      }
    });
    this.form.controls.sourceAccountId.valueChanges.pipe(takeUntilDestroyed()).subscribe((id) => {
      this.sourceId.set(id);
      const target = this.form.controls.targetAccountId;
      if (target.value !== null && !this.targets().some((account) => account.id === target.value)) {
        target.setValue(null);
      }
      this.syncUsdAmount();
    });
    this.form.controls.targetAccountId.valueChanges.pipe(takeUntilDestroyed()).subscribe((id) => {
      this.targetId.set(id);
      // Al volverse a la misma moneda, el campo de dólares deja de pedirse y de contar para la validez.
      this.syncUsdAmount();
    });
  }

  /** Carga los valores de la transferencia: en monedas distintas, el de pesos va a `amount` y el de dólares a `usdAmount`. */
  private load(transfer: Transfer): void {
    const { controls } = this.form;
    this.error.set(null);
    controls.sourceAccountId.setValue(transfer.sourceAccountId);
    controls.targetAccountId.setValue(transfer.targetAccountId);
    controls.date.setValue(transfer.date);
    const cross = transfer.sourceCurrency !== transfer.targetCurrency;
    const sourceIsArs = transfer.sourceCurrency !== 'USD';
    controls.amount.setValue(
      formatAmountInput(cross && !sourceIsArs ? transfer.targetAmount : transfer.sourceAmount),
    );
    controls.usdAmount.setValue(cross ? formatAmountInput(sourceIsArs ? transfer.targetAmount : transfer.sourceAmount) : '');
    controls.note.setValue(transfer.note ?? '');
  }

  private syncUsdAmount(): void {
    const usd = this.form.controls.usdAmount;
    if (this.crossCurrency() && usd.disabled) {
      usd.enable();
    } else if (!this.crossCurrency() && usd.enabled) {
      usd.disable();
      usd.reset('');
    }
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
    // Con monedas distintas, `amount` es el de pesos y `usdAmount` el de dólares; cada uno va a la cuenta de su moneda.
    const usdAmount = this.crossCurrency() ? parseAmount(raw.usdAmount)! : amount;
    const sourceIsArs = this.currencies()?.source !== 'USD';
    const sourceAmount = sourceIsArs ? amount : usdAmount;
    const targetAmount = sourceIsArs ? usdAmount : amount;
    const note = raw.note.trim();
    this.error.set(null);
    this.submitting.set(true);
    const request = {
      sourceAccountId: raw.sourceAccountId!,
      targetAccountId: raw.targetAccountId!,
      date: raw.date,
      sourceAmount,
      targetAmount,
      ...(note === '' ? {} : { note }),
    };
    const editing = this.transfer();
    (editing === null ? this.api.createTransfer(request) : this.api.updateTransfer(editing.id, request))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (transfer) => {
          this.submitting.set(false);
          if (editing === null) {
            this.form.controls.amount.reset('');
            this.form.controls.usdAmount.reset('');
            this.form.controls.note.reset('');
            this.form.controls.amount.markAsUntouched();
          }
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
