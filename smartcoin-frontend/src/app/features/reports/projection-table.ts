import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Projection, ProjectionMonth, ProjectionSubtotal } from '../../api';
import { MoneyPipe } from '../../shared/pipes/money.pipe';

type Currency = ProjectionSubtotal.CurrencyEnum;

/** Una columna de cuenta, con el lugar que ocupa en `balances` de cada mes. */
export interface AccountColumn {
  index: number;
  name: string;
}

/** Las cuentas de una moneda, seguidas de su subtotal. */
export interface CurrencyGroup {
  currency: Currency;
  accounts: AccountColumn[];
}

/**
 * La tabla de la proyección de saldos (HU-35, RN-46): un mes por fila y, en columnas, las cuentas agrupadas por moneda
 * con el subtotal de cada moneda. No suma ni ordena nada: los saldos y subtotales vienen calculados del backend; solo
 * agrupa las columnas por moneda. Un saldo negativo lleva el signo menos y la marca «Faltante», como el flujo de caja.
 * El encabezado queda fijo al recorrer los 25 meses y la columna del mes, al recorrer las cuentas.
 */
@Component({
  selector: 'app-projection-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MoneyPipe],
  templateUrl: './projection-table.html',
  styleUrl: './projection-table.scss',
})
export class ProjectionTable {
  readonly projection = input.required<Projection>();

  protected readonly groups = computed<CurrencyGroup[]>(() => {
    const groups: CurrencyGroup[] = [];
    this.projection().accounts.forEach((account, index) => {
      let group = groups.find((g) => g.currency === account.currency);
      if (!group) {
        group = { currency: account.currency, accounts: [] };
        groups.push(group);
      }
      group.accounts.push({ index, name: account.name });
    });
    return groups;
  });

  /** El saldo de la cuenta en ese mes, o `null` si la respuesta no lo trae. */
  protected balance(month: ProjectionMonth, index: number): number | null {
    return month.balances[index]?.balance ?? null;
  }

  protected subtotal(month: ProjectionMonth, currency: Currency): number | null {
    return month.subtotals.find((s) => s.currency === currency)?.balance ?? null;
  }

  /** Primer día del mes para dar formato: la respuesta trae `YYYY-MM`. */
  protected monthDate(month: string): string {
    return `${month}-01`;
  }

  /** Un saldo por debajo de 0 es el faltante que la pantalla quiere anticipar. */
  protected isShortfall(balance: number | null): boolean {
    return balance !== null && balance < 0;
  }
}
