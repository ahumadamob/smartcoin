import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
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
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import {
  AccountResponse,
  BudgetItemDetail,
  BudgetItemRequest,
  BudgetItemResponse,
  CategoryResponse,
  ConceptosService,
} from '../../api';
import { messageFor } from '../../core/error-messages';
import { formatAmountInput, parseAmount } from '../../shared/amount';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/confirm-dialog';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { CURRENCY_LABELS } from '../accounts/account-labels';
import {
  ESTIMATION_RULES,
  KIND_LABELS,
  KINDS,
  OFFSET_LABEL,
  PERIODICITIES,
  PERIODICITY_LABELS,
} from './budget-item-labels';
import { amountChangeMessage } from './budget-item-amount-change';

const PERIOD_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

function notBlank(control: AbstractControl): ValidationErrors | null {
  return typeof control.value === 'string' && control.value.trim() === '' ? { blank: true } : null;
}

/** Monto con coma decimal y sin signo: el monto vigente no puede ser negativo. */
function amountFormat(control: AbstractControl): ValidationErrors | null {
  const text = (control.value as string).trim();
  return text === '' || (parseAmount(text) !== null && !text.startsWith('-')) ? null : { amount: true };
}

/** Período `YYYY-MM`. Los navegadores sin selector de mes dejan escribirlo a mano. */
function periodFormat(control: AbstractControl): ValidationErrors | null {
  const text = control.value as string;
  return text === '' || PERIOD_PATTERN.test(text) ? null : { period: true };
}

function wholeNumber(control: AbstractControl): ValidationErrors | null {
  const value = control.value as number | null;
  return value === null || Number.isInteger(value) ? null : { whole: true };
}

/**
 * Alta de un Concepto recurrente (HU-10) o en cuotas (HU-11) y su edición (HU-13). Solo valida el formato; las reglas
 * (rango del período de inicio, fin anterior al inicio, cuenta o categoría inexistentes, primera cuota mayor que el
 * total, cuenta de otra moneda) las decide el backend y acá se muestra su error. El monto se convierte de coma decimal
 * a número antes de enviarlo. En un plan de cuotas el período de fin no se ingresa: se calcula.
 *
 * Con un `item`, el tipo, la periodicidad, los períodos de inicio y de fin y las cuotas quedan deshabilitados con el
 * motivo que informa el backend (RN-15), y un cambio de monto vigente pide confirmación antes de guardar.
 */
@Component({
  selector: 'app-budget-item-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatRadioModule,
    MatSelectModule,
  ],
  templateUrl: './budget-item-form.html',
  styleUrl: './budget-item-form.scss',
})
export class BudgetItemForm {
  private readonly api = inject(ConceptosService);
  private readonly dialog = inject(MatDialog);
  private readonly money = new MoneyPipe();

  /** Cuentas del usuario, para elegir la cuenta por defecto. */
  readonly accounts = input.required<AccountResponse[]>();
  readonly categories = input.required<CategoryResponse[]>();
  /** Período de inicio sugerido para un alta (`YYYY-MM`): el mes actual. */
  readonly suggestedStartPeriod = input<string>('');
  /** El Concepto que se edita; `null` para un alta. */
  readonly item = input<BudgetItemDetail | null>(null);

  /** El Concepto se creó; trae el resumen de las partidas generadas. */
  readonly saved = output<BudgetItemResponse>();
  /** El Concepto se editó; trae sus datos y conteos actualizados. */
  readonly updated = output<BudgetItemDetail>();

  protected readonly kinds = KINDS;
  protected readonly kindLabels = KIND_LABELS;
  protected readonly periodicities = PERIODICITIES;
  protected readonly periodicityLabels = PERIODICITY_LABELS;
  protected readonly estimationRules = ESTIMATION_RULES;
  protected readonly offsetLabel = OFFSET_LABEL;
  protected readonly currencyLabels = CURRENCY_LABELS;

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, notBlank, Validators.maxLength(100)],
    }),
    kind: new FormControl<BudgetItemRequest.KindEnum | null>(null, Validators.required),
    defaultAccountId: new FormControl<number | null>(null, Validators.required),
    categoryId: new FormControl<number | null>(null),
    periodicity: new FormControl<BudgetItemRequest.PeriodicityEnum | null>('MONTHLY', Validators.required),
    dueDay: new FormControl<number | null>(null, [
      Validators.required,
      Validators.min(1),
      Validators.max(31),
      wholeNumber,
    ]),
    dueInPreviousMonth: new FormControl(false, { nonNullable: true }),
    startPeriod: new FormControl('', { nonNullable: true, validators: [Validators.required, periodFormat] }),
    inInstallments: new FormControl(false, { nonNullable: true }),
    installmentsTotal: new FormControl<number | null>(null, [Validators.required, Validators.min(1), wholeNumber]),
    // Vacía vale 1: el backend la completa.
    firstInstallmentNumber: new FormControl<number | null>(1, [Validators.min(1), wholeNumber]),
    endPeriod: new FormControl('', { nonNullable: true, validators: periodFormat }),
    estimationRule: new FormControl<BudgetItemRequest.EstimationRuleEnum | null>('LAST_VALUE', Validators.required),
    currentAmount: new FormControl('', { nonNullable: true, validators: [Validators.required, amountFormat] }),
  });
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);

  protected readonly inInstallments = toSignal(this.form.controls.inInstallments.valueChanges, {
    initialValue: false,
  });

  protected readonly editing = computed(() => this.item() !== null);
  /** Motivo de cada dato bloqueado, tal como lo informa el backend (RN-15); `null` si se puede editar. */
  protected readonly reasons = computed(() => {
    const editability = this.item()?.editability;
    const reason = (state: { editable: boolean; reason?: string | null } | undefined) =>
      state && !state.editable ? (state.reason ?? null) : null;
    return {
      kind: reason(editability?.kind),
      periodicity: reason(editability?.periodicity),
      startPeriod: reason(editability?.startPeriod),
      endPeriod: reason(editability?.endPeriod),
      installments: reason(editability?.installments),
    };
  });

  private readonly directive = viewChild.required(FormGroupDirective);

  constructor() {
    effect(() => {
      const item = this.item();
      const suggested = this.suggestedStartPeriod();
      untracked(() => (item === null ? this.form.controls.startPeriod.reset(suggested) : this.load(item)));
    });
    this.applyInstallments(false);
    // Al editar, qué campos se habilitan lo decide `load` (todo lo de cuotas y el fin están bloqueados).
    this.form.controls.inInstallments.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((on) => !this.editing() && this.applyInstallments(on));
  }

  /** Con cuotas, el total y la primera cuota cuentan y el fin no; sin cuotas, al revés. Un campo deshabilitado no valida. */
  private applyInstallments(on: boolean): void {
    const { installmentsTotal, firstInstallmentNumber, endPeriod } = this.form.controls;
    if (on) {
      installmentsTotal.enable();
      firstInstallmentNumber.enable();
      endPeriod.reset('');
      endPeriod.disable();
    } else {
      installmentsTotal.disable();
      firstInstallmentNumber.disable();
      endPeriod.enable();
    }
  }

  /** Carga el Concepto que se edita y bloquea lo que no se puede cambiar. */
  private load(item: BudgetItemDetail): void {
    this.error.set(null);
    this.form.enable();
    this.form.reset({
      name: item.name,
      kind: item.kind,
      defaultAccountId: item.defaultAccountId,
      categoryId: item.categoryId ?? null,
      periodicity: item.periodicity,
      dueDay: item.dueDay,
      dueInPreviousMonth: item.dueMonthOffset === -1,
      startPeriod: item.startPeriod,
      inInstallments: item.installmentsTotal != null,
      installmentsTotal: item.installmentsTotal ?? null,
      firstInstallmentNumber: item.firstInstallmentNumber ?? 1,
      endPeriod: item.endPeriod ?? '',
      estimationRule: item.estimationRule,
      currentAmount: formatAmountInput(item.currentAmount),
    });
    const { kind, periodicity, startPeriod, inInstallments, installmentsTotal, firstInstallmentNumber, endPeriod } =
      this.form.controls;
    for (const locked of [
      kind,
      periodicity,
      startPeriod,
      inInstallments,
      installmentsTotal,
      firstInstallmentNumber,
      endPeriod,
    ]) {
      locked.disable();
    }
  }

  private defaults() {
    return {
      name: '',
      kind: null,
      defaultAccountId: null,
      categoryId: null,
      periodicity: 'MONTHLY' as const,
      dueDay: null,
      dueInPreviousMonth: false,
      startPeriod: this.suggestedStartPeriod(),
      endPeriod: '',
      inInstallments: false,
      installmentsTotal: null,
      firstInstallmentNumber: 1,
      estimationRule: 'LAST_VALUE' as const,
      currentAmount: '',
    };
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const request: BudgetItemRequest = {
      name: value.name.trim(),
      kind: value.kind!,
      defaultAccountId: value.defaultAccountId!,
      categoryId: value.categoryId,
      periodicity: value.periodicity!,
      dueDay: value.dueDay!,
      dueMonthOffset: value.dueInPreviousMonth ? -1 : 0,
      startPeriod: value.startPeriod,
      endPeriod: value.inInstallments || value.endPeriod === '' ? null : value.endPeriod,
      estimationRule: value.estimationRule!,
      currentAmount: parseAmount(value.currentAmount)!,
      ...(value.inInstallments
        ? { installmentsTotal: value.installmentsTotal!, firstInstallmentNumber: value.firstInstallmentNumber }
        : {}),
    };
    const item = this.item();
    if (item === null) {
      this.create(request);
    } else if (request.currentAmount !== item.currentAmount) {
      this.confirmAmountChange(item, request);
    } else {
      this.update(item, request);
    }
  }

  private create(request: BudgetItemRequest): void {
    this.error.set(null);
    this.submitting.set(true);
    this.api.createBudgetItem(request).subscribe({
      next: (item) => {
        this.submitting.set(false);
        // El directivo recuerda que el formulario se envió: sin resetearlo a él, los campos vacíos se verían inválidos.
        this.directive().resetForm(this.defaults());
        this.saved.emit(item);
      },
      error: (e: unknown) => {
        this.submitting.set(false);
        this.error.set(messageFor(e));
      },
    });
  }

  /** Un cambio de monto vigente reemplaza partidas (RN-15, S-11): antes de guardar se dice cuántas y cuáles no. */
  private confirmAmountChange(item: BudgetItemDetail, request: BudgetItemRequest): void {
    const data: ConfirmDialogData = {
      title: 'Cambiar el monto vigente',
      message: amountChangeMessage(
        this.money.transform(item.currentAmount, item.currency),
        this.money.transform(request.currentAmount, item.currency),
        item.entryCounts,
      ),
      confirmLabel: 'Cambiar monto',
    };
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, { data })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.update(item, request);
        }
      });
  }

  private update(item: BudgetItemDetail, request: BudgetItemRequest): void {
    this.error.set(null);
    this.submitting.set(true);
    this.api.updateBudgetItem(item.id, request).subscribe({
      next: (updated) => {
        this.submitting.set(false);
        this.updated.emit(updated);
      },
      error: (e: unknown) => {
        this.submitting.set(false);
        this.error.set(messageFor(e));
      },
    });
  }
}
