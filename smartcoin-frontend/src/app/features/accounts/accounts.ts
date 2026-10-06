import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { AccountListResponse, AccountResponse, AutenticacinService, CurrencySubtotal, CuentasService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/confirm-dialog';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { AccountForm } from './account-form';
import { ACCOUNT_TYPE_LABELS, CURRENCIES, CURRENCY_LABELS } from './account-labels';

/** Las cuentas de una moneda y su subtotal, calculado por el backend. */
interface CurrencyGroup {
  currency: AccountResponse['currency'];
  label: string;
  accounts: AccountResponse[];
  subtotal: number;
}

/** Cuentas (HU-07): lista agrupada por moneda, y alta, edición y eliminación. Las reglas las decide el backend. */
@Component({
  selector: 'app-accounts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AccountForm, DatePipe, MatButtonModule, MatCardModule, MoneyPipe],
  templateUrl: './accounts.html',
  styleUrl: './accounts.scss',
})
export class Accounts implements OnInit {
  private readonly accountsApi = inject(CuentasService);
  private readonly authApi = inject(AutenticacinService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly dateFormat = DATE_FORMAT;
  protected readonly typeLabels = ACCOUNT_TYPE_LABELS;

  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly subtotals = signal<CurrencySubtotal[]>([]);
  /** Primer día del período inicial del usuario (`YYYY-MM-DD`): la fecha de apertura que se sugiere en un alta. */
  protected readonly suggestedOpeningDate = signal('');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** `'new'` con el formulario de alta abierto, la cuenta que se edita, o `null` con el formulario cerrado. */
  protected readonly formState = signal<'new' | AccountResponse | null>(null);
  protected readonly editedAccount = computed(() => {
    const state = this.formState();
    return state === 'new' ? null : state;
  });

  protected readonly groups = computed<CurrencyGroup[]>(() =>
    CURRENCIES.map((currency) => ({
      currency,
      label: CURRENCY_LABELS[currency],
      accounts: this.accounts().filter((account) => account.currency === currency),
      subtotal: this.subtotals().find((s) => s.currency === currency)?.balance ?? 0,
    })).filter((group) => group.accounts.length > 0),
  );

  ngOnInit(): void {
    forkJoin({ user: this.authApi.me(), accounts: this.accountsApi.listAccounts() }).subscribe({
      next: ({ user, accounts }) => {
        this.suggestedOpeningDate.set(`${user.startPeriod}-01`);
        this.setList(accounts);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.error.set(messageFor(e));
        this.loading.set(false);
      },
    });
  }

  protected openNew(): void {
    this.error.set(null);
    this.formState.set('new');
  }

  protected openEdit(account: AccountResponse): void {
    this.error.set(null);
    this.formState.set(account);
  }

  protected closeForm(): void {
    this.formState.set(null);
  }

  protected onSaved(account: AccountResponse): void {
    const wasNew = this.formState() === 'new';
    this.formState.set(null);
    this.reload();
    this.snackBar.open(wasNew ? `Cuenta «${account.name}» creada.` : `Cuenta «${account.name}» guardada.`, undefined, {
      duration: 4000,
    });
  }

  protected confirmDelete(account: AccountResponse): void {
    const data: ConfirmDialogData = {
      title: 'Eliminar cuenta',
      message: `Vas a eliminar la cuenta «${account.name}». Esta acción no se puede deshacer.`,
      confirmLabel: 'Eliminar',
    };
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, { data })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.delete(account);
        }
      });
  }

  private delete(account: AccountResponse): void {
    this.error.set(null);
    this.accountsApi.deleteAccount(account.id).subscribe({
      next: () => {
        if (this.editedAccount()?.id === account.id) {
          this.formState.set(null);
        }
        this.reload();
        this.snackBar.open(`Cuenta «${account.name}» eliminada.`, undefined, { duration: 4000 });
      },
      error: (e: unknown) => this.error.set(messageFor(e)),
    });
  }

  private setList(list: AccountListResponse): void {
    this.accounts.set(list.accounts);
    this.subtotals.set(list.subtotals);
  }

  private reload(): void {
    this.accountsApi.listAccounts().subscribe({
      next: (accounts) => this.setList(accounts),
      error: (e: unknown) => this.error.set(messageFor(e)),
    });
  }
}
