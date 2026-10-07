import {
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
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
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { BudgetItemForm } from './budget-item-form';

/** Mes actual del navegador (`YYYY-MM`). Solo es el valor sugerido: el rango válido lo decide el backend. */
function currentPeriod(): string {
  const today = new Date();
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * Conceptos (HU-10): alta de un Concepto recurrente. Después de guardar muestra el resumen de las partidas que generó
 * el backend. La lista de Conceptos es HU-14 y las partidas se ven en la vista del mes (HU-15).
 */
@Component({
  selector: 'app-budget-items',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink, PeriodPipe, BudgetItemForm],
  templateUrl: './budget-items.html',
  styleUrl: './budget-items.scss',
})
export class BudgetItems implements OnInit {
  private readonly accountsApi = inject(CuentasService);
  private readonly categoriesApi = inject(CategorasService);

  protected readonly dateFormat = DATE_FORMAT;
  protected readonly suggestedStartPeriod = currentPeriod();

  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly categories = signal<CategoryResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** El último Concepto creado en esta pantalla, con su resumen de generación. */
  protected readonly created = signal<BudgetItemResponse | null>(null);

  private readonly summary = viewChild<ElementRef<HTMLElement>>('summary');

  constructor() {
    // El botón de guardar queda al final del formulario: el resumen, que está arriba, se trae a la vista.
    effect(() => {
      // Depende también del Concepto creado: en el segundo guardado el elemento del resumen es el mismo.
      if (this.created() !== null) {
        this.summary()?.nativeElement.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
      }
    });
  }

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
}
