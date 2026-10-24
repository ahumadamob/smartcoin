import { formatDate } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  LOCALE_ID,
  OnInit,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Message } from 'primeng/message';
import { forkJoin } from 'rxjs';
import {
  AccountResponse,
  BudgetItemResponse,
  CategorasService,
  CategoryResponse,
  CuentasService,
} from '../../api';
import { messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { Notices } from '../../core/notices';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { BudgetItemForm } from './budget-item-form';

/** Mes actual del navegador (`YYYY-MM`). Solo es el valor sugerido: el rango válido lo decide el backend. */
function currentPeriod(): string {
  const today = new Date();
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * Conceptos (HU-10, HU-11): alta de un Concepto recurrente o en cuotas. Después de guardar muestra el resumen de las
 * partidas que generó el backend. La lista de Conceptos es HU-14 y las partidas se ven en la vista del mes (HU-15).
 */
@Component({
  selector: 'app-budget-item-new',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BudgetItemForm, Message, RouterLink],
  templateUrl: './budget-item-new.html',
  styleUrl: './budget-item-new.scss',
})
export class BudgetItemNew implements OnInit {
  private readonly accountsApi = inject(CuentasService);
  private readonly categoriesApi = inject(CategorasService);

  private readonly router = inject(Router);
  private readonly notices = inject(Notices);
  private readonly locale = inject(LOCALE_ID);
  private readonly period = new PeriodPipe();

  protected readonly suggestedStartPeriod = currentPeriod();

  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly categories = signal<CategoryResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    forkJoin({
      accounts: this.accountsApi.listAccounts(),
      categories: this.categoriesApi.listCategories(),
    }).subscribe({
      next: ({ accounts, categories }) => {
        this.accounts.set(accounts.accounts);
        this.categories.set(categories);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.error.set(messageFor(e));
        this.loading.set(false);
      },
    });
  }

  /** Al crear el Concepto vuelve a la lista y avisa qué partidas generó el backend (antes era un resumen en la pantalla). */
  protected onCreated(item: BudgetItemResponse): void {
    this.notices.show(this.summary(item), { duration: 10000 });
    void this.router.navigate(['/conceptos']);
  }

  private summary(item: BudgetItemResponse): string {
    const generation = item.generation;
    const parts = [`Concepto «${item.name}» creado.`];
    parts.push(
      generation.entryCount === 1
        ? `Se generó 1 partida, en ${this.period.transform(generation.firstPeriod)}.`
        : `Se generaron ${generation.entryCount} partidas, de ${this.period.transform(generation.firstPeriod)} a ${this.period.transform(generation.lastPeriod)}.`,
    );
    parts.push(
      `Primer vencimiento: ${formatDate(generation.firstDueDate, DATE_FORMAT, this.locale)}.`,
    );
    const total = item.installmentsTotal;
    if (total) {
      parts.push(
        generation.firstInstallment === generation.lastInstallment
          ? `Es la cuota ${generation.lastInstallment} de ${total}.`
          : `Son las cuotas ${generation.firstInstallment} a ${generation.lastInstallment} de ${total}.`,
      );
      if (generation.lastInstallment !== total) {
        parts.push(
          `El plan termina en ${this.period.transform(item.endPeriod)}; las demás cuotas se generan a medida que avance el horizonte.`,
        );
      }
    }
    return parts.join(' ');
  }
}
