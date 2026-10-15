import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { AccountResponse, CuentasService, Transfer, TransferenciasService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { TransferForm } from './transfer-form';

/**
 * Transferencias (HU-27): lista del rango de fechas y alta entre cuentas de la misma moneda. Sin fechas, el backend
 * devuelve el mes actual. Las reglas las decide el backend.
 */
@Component({
  selector: 'app-transfers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule, MoneyPipe, ReactiveFormsModule, TransferForm],
  templateUrl: './transfers.html',
  styleUrl: './transfers.scss',
})
export class Transfers implements OnInit {
  private readonly transfersApi = inject(TransferenciasService);
  private readonly accountsApi = inject(CuentasService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly dateFormat = DATE_FORMAT;

  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly transfers = signal<Transfer[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly formOpen = signal(false);

  protected readonly range = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  ngOnInit(): void {
    forkJoin({ accounts: this.accountsApi.listAccounts(), transfers: this.transfersApi.listTransfers() }).subscribe({
      next: ({ accounts, transfers }) => {
        this.accounts.set(accounts.accounts);
        this.transfers.set(transfers);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.error.set(messageFor(e));
        this.loading.set(false);
      },
    });
  }

  protected openForm(): void {
    this.error.set(null);
    this.formOpen.set(true);
  }

  protected closeForm(): void {
    this.formOpen.set(false);
  }

  protected onSaved(): void {
    this.snackBar.open('Transferencia registrada.', undefined, { duration: 4000 });
    this.search();
  }

  protected search(): void {
    const { from, to } = this.range.getRawValue();
    this.error.set(null);
    this.transfersApi.listTransfers(from || undefined, to || undefined).subscribe({
      next: (transfers) => this.transfers.set(transfers),
      error: (e: unknown) => this.error.set(messageFor(e)),
    });
  }
}
