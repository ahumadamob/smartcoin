import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { catchError, EMPTY, Subject, switchMap, tap } from 'rxjs';
import { BudgetItemListItem, CategorasService, CategoryResponse, ConceptosService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { dueText, KIND_LABELS, PERIODICITY_LABELS, statusText } from './budget-item-labels';

type KindFilter = 'ALL' | BudgetItemListItem.KindEnum;
/** `ALL`, `NONE` («Sin categoría») o el id de una categoría. */
type CategoryFilter = 'ALL' | 'NONE' | number;

/**
 * Conceptos (HU-14): la lista de todos los Conceptos del usuario, con filtros por tipo y categoría. El orden, el
 * estado y las cuotas vienen del backend. El alta está en `/conceptos/nuevo` y la edición en `/conceptos/:id/editar`.
 */
@Component({
  selector: 'app-budget-items',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatSelectModule, MatTableModule, MoneyPipe, PeriodPipe, RouterLink],
  templateUrl: './budget-items.html',
  styleUrl: './budget-items.scss',
})
export class BudgetItems implements OnInit {
  private readonly itemsApi = inject(ConceptosService);
  private readonly categoriesApi = inject(CategorasService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly columns = [
    'name',
    'kind',
    'account',
    'category',
    'periodicity',
    'due',
    'estimation',
    'amount',
    'status',
    'actions',
  ];
  // `let item` en las celdas de la tabla es `any`: los textos se indexan por `string`.
  protected readonly kindLabels: Record<string, string> = KIND_LABELS;
  protected readonly periodicityLabels: Record<string, string> = PERIODICITY_LABELS;
  protected readonly estimationLabels: Record<string, string> = {
    LAST_VALUE: 'Último valor',
    AVERAGE_LAST_3: 'Promedio de los últimos 3',
  };
  protected readonly dueText = dueText;
  protected readonly statusText = statusText;

  protected readonly items = signal<BudgetItemListItem[]>([]);
  protected readonly categories = signal<CategoryResponse[]>([]);
  protected readonly kindFilter = signal<KindFilter>('ALL');
  protected readonly categoryFilter = signal<CategoryFilter>('ALL');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  private readonly requests = new Subject<void>();

  ngOnInit(): void {
    // Cada cambio de filtro cancela el pedido anterior: no puede llegar una respuesta vieja después de una nueva.
    this.requests
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.error.set(null);
        }),
        switchMap(() => {
          const kind = this.kindFilter();
          const category = this.categoryFilter();
          return this.itemsApi
            .listBudgetItems(
              kind === 'ALL' ? undefined : kind,
              typeof category === 'number' ? category : undefined,
              category === 'NONE' ? true : undefined,
            )
            .pipe(
              catchError((e: unknown) => {
                this.error.set(messageFor(e));
                this.loading.set(false);
                return EMPTY;
              }),
            );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((items) => {
        this.items.set(items);
        this.loading.set(false);
      });

    this.categoriesApi.listCategories().subscribe({
      next: (categories) => this.categories.set(categories),
      error: (e: unknown) => this.error.set(messageFor(e)),
    });
    this.requests.next();
  }

  protected hasFilters(): boolean {
    return this.kindFilter() !== 'ALL' || this.categoryFilter() !== 'ALL';
  }

  protected setKind(kind: KindFilter): void {
    this.kindFilter.set(kind);
    this.requests.next();
  }

  protected setCategory(category: CategoryFilter): void {
    this.categoryFilter.set(category);
    this.requests.next();
  }

  protected clearFilters(): void {
    this.kindFilter.set('ALL');
    this.categoryFilter.set('ALL');
    this.requests.next();
  }
}
