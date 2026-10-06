import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
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
import { AccountRequest, AccountResponse, CuentasService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { ACCOUNT_TYPE_LABELS, ACCOUNT_TYPES, CURRENCIES, CURRENCY_LABELS } from './account-labels';
import { formatAmountInput, parseAmount } from './amount';

function notBlank(control: AbstractControl): ValidationErrors | null {
  return typeof control.value === 'string' && control.value.trim() === '' ? { blank: true } : null;
}

function amountFormat(control: AbstractControl): ValidationErrors | null {
  const text = control.value as string;
  return text === '' || parseAmount(text) !== null ? null : { amount: true };
}

/**
 * Alta y edición de una cuenta (HU-07). Con una cuenta, los campos que el backend marca como no editables
 * (`editability`) quedan deshabilitados y muestran su motivo: la regla (RN-33) la decide el backend, acá solo se
 * muestra. Solo se valida el formato; el saldo se convierte de coma decimal a número antes de enviarlo.
 */
@Component({
  selector: 'app-account-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  templateUrl: './account-form.html',
  styleUrl: './account-form.scss',
})
export class AccountForm {
  private readonly api = inject(CuentasService);

  /** La cuenta que se edita; `null` para un alta. */
  readonly account = input<AccountResponse | null>(null);
  /** Fecha de apertura sugerida para un alta: el primer día del período inicial (`YYYY-MM-DD`). */
  readonly suggestedOpeningDate = input.required<string>();

  /** La cuenta se guardó (alta o edición). */
  readonly saved = output<AccountResponse>();
  readonly cancelled = output<void>();

  protected readonly types = ACCOUNT_TYPES;
  protected readonly typeLabels = ACCOUNT_TYPE_LABELS;
  protected readonly currencies = CURRENCIES;
  protected readonly currencyLabels = CURRENCY_LABELS;

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, notBlank, Validators.maxLength(100)],
    }),
    type: new FormControl<AccountRequest.TypeEnum | null>(null, Validators.required),
    currency: new FormControl<AccountRequest.CurrencyEnum | null>(null, Validators.required),
    openingDate: new FormControl('', { nonNullable: true, validators: Validators.required }),
    initialBalance: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, amountFormat],
    }),
  });
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);

  protected readonly editing = computed(() => this.account() !== null);
  /** Motivo de cada campo bloqueado, tal como lo informa el backend; `null` si se puede editar. */
  protected readonly reasons = computed(() => {
    const editability = this.account()?.editability;
    return {
      currency: editability?.currency.editable === false ? editability.currency.reason : null,
      initialBalance:
        editability?.initialBalance.editable === false ? editability.initialBalance.reason : null,
      openingDate: editability?.openingDate.editable === false ? editability.openingDate.reason : null,
    };
  });

  constructor() {
    effect(() => this.load(this.account(), this.suggestedOpeningDate()));
  }

  private load(account: AccountResponse | null, suggestedOpeningDate: string): void {
    this.error.set(null);
    this.form.reset({
      name: account?.name ?? '',
      type: account?.type ?? null,
      currency: account?.currency ?? null,
      openingDate: account?.openingDate ?? suggestedOpeningDate,
      initialBalance: account ? formatAmountInput(account.initialBalance) : '',
    });
    this.form.enable();
    const reasons = this.reasons();
    for (const field of ['currency', 'initialBalance', 'openingDate'] as const) {
      if (reasons[field] !== null) {
        this.form.controls[field].disable();
      }
    }
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    // Los campos deshabilitados no figuran en `value`: se manda su valor actual, que el backend ve como "sin cambio".
    const raw = this.form.getRawValue();
    const request: AccountRequest = {
      name: raw.name.trim(),
      type: raw.type!,
      currency: raw.currency!,
      openingDate: raw.openingDate,
      initialBalance: parseAmount(raw.initialBalance)!,
    };
    const current = this.account();
    this.error.set(null);
    this.submitting.set(true);
    const call =
      current === null ? this.api.createAccount(request) : this.api.updateAccount(current.id, request);
    call.subscribe({
      next: (account) => {
        this.submitting.set(false);
        this.saved.emit(account);
      },
      error: (e: unknown) => {
        this.submitting.set(false);
        this.error.set(messageFor(e));
      },
    });
  }
}
