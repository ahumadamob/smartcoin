import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  AccountResponse,
  BudgetItemDetail,
  CategorasService,
  CategoryResponse,
  ConceptosService,
  CuentasService,
} from '../../api';
import { messageFor } from '../../core/error-messages';
import { BudgetItemForm } from './budget-item-form';

/**
 * Editar un Concepto (HU-13), en `/conceptos/:id/editar`. Reutiliza el formulario de alta: los datos que no se
 * pueden editar quedan deshabilitados con su motivo (RN-15). Explica cómo dar de baja un Concepto. La lista de
 * Conceptos, desde donde se llegará acá, es HU-14.
 */
@Component({
  selector: 'app-budget-item-edit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, BudgetItemForm],
  templateUrl: './budget-item-edit.html',
  styleUrl: './budget-item-edit.scss',
})
export class BudgetItemEdit implements OnInit {
  private readonly itemsApi = inject(ConceptosService);
  private readonly accountsApi = inject(CuentasService);
  private readonly categoriesApi = inject(CategorasService);
  private readonly snackBar = inject(MatSnackBar);

  private readonly route = inject(ActivatedRoute);

  protected readonly item = signal<BudgetItemDetail | null>(null);
  protected readonly accounts = signal<AccountResponse[]>([]);
  protected readonly categories = signal<CategoryResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isInteger(id) || id < 1) {
      this.error.set('El Concepto no existe.');
      this.loading.set(false);
      return;
    }
    forkJoin({
      item: this.itemsApi.getBudgetItem(id),
      accounts: this.accountsApi.listAccounts(),
      categories: this.categoriesApi.listCategories(),
    }).subscribe({
      next: ({ item, accounts, categories }) => {
        this.item.set(item);
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

  protected onUpdated(item: BudgetItemDetail): void {
    this.item.set(item);
    this.snackBar.open(`Concepto «${item.name}» guardado.`, undefined, { duration: 4000 });
  }
}
