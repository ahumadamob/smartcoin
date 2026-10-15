import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, inject, LOCALE_ID, OnInit, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatDialog } from '@angular/material/dialog';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { AccountResponse, CuentasService, Transfer, TransferenciasService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/confirm-dialog';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { TransferForm } from './transfer-form';
import { exchangeKind } from './transfer-kind';

/**
 * Transferencias (HU-27, HU-29): lista del rango de fechas, alta, corrección y eliminación. Sin fechas, el backend
 * devuelve el mes actual. Las reglas las decide el backend; `editable` solo dice si ofrecer las acciones (D-41).
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
  private readonly dialog = inject(MatDialog);
  private readonly datePipe = new DatePipe(inject(LOCALE_ID));
  private readonly newButton = viewChild('newButton', { read: ElementRef<HTMLButtonElement> });

  private readonly money = new MoneyPipe();

  protected readonly dateFormat = DATE_FORMAT;

  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly transfers = signal<Transfer[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly formOpen = signal(false);
  /** La transferencia que se está corrigiendo; con el formulario abierto y sin ella, es un alta. */
  protected readonly editing = signal<Transfer | null>(null);

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
    this.editing.set(null);
    this.formOpen.set(true);
  }

  protected edit(transfer: Transfer): void {
    this.error.set(null);
    this.editing.set(transfer);
    this.formOpen.set(true);
  }

  protected closeForm(): void {
    this.formOpen.set(false);
    this.editing.set(null);
  }

  protected confirmDelete(transfer: Transfer): void {
    const data: ConfirmDialogData = {
      title: 'Eliminar transferencia',
      message: this.deleteMessage(transfer),
      confirmLabel: 'Eliminar',
    };
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, { data })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.delete(transfer);
        }
      });
  }

  /** Exactamente cuál: fecha, cuentas, monto o montos y, entre monedas distintas, tipo de cambio. */
  private deleteMessage(transfer: Transfer): string {
    const date = this.datePipe.transform(transfer.date, DATE_FORMAT);
    const source = this.money.transform(transfer.sourceAmount, transfer.sourceCurrency);
    const amounts =
      transfer.sourceCurrency === transfer.targetCurrency
        ? source
        : `${source} → ${this.money.transform(transfer.targetAmount, transfer.targetCurrency)}`;
    const rate =
      transfer.exchangeRate != null ? `, tipo de cambio ${this.money.transform(transfer.exchangeRate, 'ARS')}` : '';
    return (
      `Vas a eliminar la transferencia del ${date}: ${transfer.sourceAccountName} → ` +
      `${transfer.targetAccountName}, ${amounts}${rate}. Los saldos de las dos cuentas se recalculan. ` +
      'Esta acción no se puede deshacer.'
    );
  }

  private delete(transfer: Transfer): void {
    this.error.set(null);
    this.transfersApi.deleteTransfer(transfer.id).subscribe({
      next: () => {
        if (this.editing()?.id === transfer.id) {
          this.closeForm();
        }
        this.snackBar.open('Transferencia eliminada.', undefined, { duration: 4000 });
        this.search();
        // La fila ya no está: el foco pasa a la acción principal de la pantalla.
        this.newButton()?.nativeElement.focus();
      },
      error: (e: unknown) => {
        this.error.set(messageFor(e));
        // El estado del mes pudo cambiar: se vuelve a pedir la lista.
        this.reload();
      },
    });
  }

  protected kindOf(transfer: Transfer): string | null {
    return exchangeKind(transfer.sourceCurrency, transfer.targetCurrency);
  }

  protected onSaved(transfer: Transfer): void {
    const kind = this.kindOf(transfer);
    const rate = transfer.exchangeRate;
    // Entre monedas distintas, el tipo de cambio que calculó el backend se ve apenas se guarda (HU-28).
    const edited = this.editing() !== null;
    const verb = edited ? 'corregida' : 'registrada';
    const message =
      kind !== null && rate != null
        ? `${kind} ${verb}. Tipo de cambio: ${this.money.transform(rate, 'ARS')}.`
        : `Transferencia ${verb}.`;
    this.snackBar.open(message, undefined, { duration: 6000 });
    if (edited) {
      this.closeForm();
      this.newButton()?.nativeElement.focus();
    }
    this.search();
  }

  protected search(): void {
    this.error.set(null);
    this.reload();
  }

  /** Vuelve a pedir la lista sin tocar el error que ya se muestra. */
  private reload(): void {
    const { from, to } = this.range.getRawValue();
    this.transfersApi.listTransfers(from || undefined, to || undefined).subscribe({
      next: (transfers) => this.transfers.set(transfers),
      error: (e: unknown) => this.error.set(messageFor(e)),
    });
  }
}
