import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Subscription } from 'rxjs';
import { Projection, ReportesService } from '../../api';
import { messageFor } from '../../core/error-messages';
import { ProjectionTable } from './projection-table';

/**
 * Proyección de saldos por cuenta (HU-35, RN-46): el saldo proyectado de cada cuenta al último día de cada mes, desde
 * el actual hasta el horizonte. «Hasta» es opcional y limita los meses; el backend valida que no pase del horizonte ni
 * sea anterior al mes actual. No calcula nada: saldos y subtotales los decide el backend.
 */
@Component({
  selector: 'app-projection',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatInputModule, ProjectionTable, ReactiveFormsModule],
  templateUrl: './projection.html',
  styleUrl: './projection.scss',
})
export class ProjectionPage implements OnInit {
  private readonly reports = inject(ReportesService);
  private readonly destroyRef = inject(DestroyRef);
  private pending: Subscription | null = null;

  protected readonly projection = signal<Projection | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly filters = new FormGroup({
    until: new FormControl('', { nonNullable: true }),
  });

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => this.pending?.unsubscribe());
    this.search();
  }

  /** Pide la proyección hasta el mes elegido (o el horizonte); un pedido nuevo cancela el anterior. */
  protected search(): void {
    const { until } = this.filters.getRawValue();
    this.pending?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.pending = this.reports.getProjection(until || undefined).subscribe({
      next: (projection) => {
        this.projection.set(projection);
        // Sin «Hasta», la pantalla muestra el horizonte que resolvió el backend, sin pisar lo que el usuario escribió.
        const control = this.filters.controls.until;
        if (!until && control.value === '') {
          control.setValue(projection.until, { emitEvent: false });
        }
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.projection.set(null);
        this.error.set(messageFor(e));
        this.loading.set(false);
      },
    });
  }
}
