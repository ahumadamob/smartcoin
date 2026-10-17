import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Subscription } from 'rxjs';
import { BudgetGrid, ReportesService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { BudgetGridTable } from './budget-grid-table';

/**
 * Vista de varios meses (HU-36, D-48): los Conceptos mes a mes, como en una planilla. «Desde» y «Hasta» son opcionales;
 * sin elegir, el backend resuelve 12 meses desde el actual. El backend valida el rango (período inicial, horizonte y
 * 24 meses como máximo) y calcula estimados y totales. Un clic en una celda abre el presupuesto de ese mes.
 */
@Component({
  selector: 'app-budget-grid',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BudgetGridTable, MatButtonModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule],
  templateUrl: './budget-grid.html',
  styleUrl: './budget-grid.scss',
})
export class BudgetGridPage implements OnInit {
  private readonly reports = inject(ReportesService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private pending: Subscription | null = null;

  protected readonly grid = signal<BudgetGrid | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly filters = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => this.pending?.unsubscribe());
    this.search();
  }

  /** Pide la grilla del rango elegido (o el de por defecto); un pedido nuevo cancela el anterior. */
  protected search(): void {
    const { from, to } = this.filters.getRawValue();
    this.pending?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.pending = this.reports.getBudgetGrid(from || undefined, to || undefined).subscribe({
      next: (grid) => {
        this.grid.set(grid);
        // Sin extremos, la pantalla muestra el rango que resolvió el backend, sin pisar lo que el usuario escribió.
        if (!from && !to) {
          this.filters.setValue({ from: grid.from, to: grid.to }, { emitEvent: false });
        }
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.grid.set(null);
        this.error.set(messageFor(e));
        this.loading.set(false);
      },
    });
  }

  protected openMonth(period: string): void {
    void this.router.navigate(['/presupuesto', period]);
  }
}
