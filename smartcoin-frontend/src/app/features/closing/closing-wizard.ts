import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, EMPTY, map, switchMap, tap } from 'rxjs';
import { CierreDeMesService, ClosingPreview } from '../../api';
import { messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { KIND_LABELS } from '../budget-items/budget-item-labels';
import { ENTRY_STATUS_LABELS } from '../budget/entry-table';
import { isPeriod } from '../budget/period-nav';
import {
  accountsByCurrency,
  blockerText,
  CLOSING_STEPS,
  closingSummary,
  UNAVAILABLE_STEP_TEXT,
} from './closing-text';

/** Por qué no se muestra la simulación: el período no existe para el usuario, o la API falló por otra causa. */
interface PreviewError {
  unavailable: boolean;
  message: string;
}

/**
 * Asistente de cierre de mes (Épica 8), en `/cierre/:period`: tres pasos (pendientes, saldos y confirmación) sobre la
 * simulación del backend (`POST /api/periods/{period}/closing-preview`), que no modifica nada.
 *
 * HU-30 hace el primer paso: si se puede cerrar y, si no, los motivos; las partidas pendientes con real y pendiente, y
 * las cuentas a conciliar con su saldo calculado al último día, agrupadas por moneda. Los otros dos pasos se ven
 * deshabilitados. Todos los montos vienen del backend: acá no se suma nada.
 */
@Component({
  selector: 'app-closing-wizard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, MoneyPipe, PeriodPipe, RouterLink],
  templateUrl: './closing-wizard.html',
  styleUrl: './closing-wizard.scss',
})
export class ClosingWizard implements OnInit {
  private readonly api = inject(CierreDeMesService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly title = viewChild<ElementRef<HTMLElement>>('title');

  protected readonly period = signal<string | null>(null);
  protected readonly preview = signal<ClosingPreview | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<PreviewError | null>(null);
  protected readonly accountGroups = computed(() => accountsByCurrency(this.preview()?.accounts ?? []));
  protected readonly closed = computed(() => this.preview()?.status === 'CLOSED');

  protected readonly steps = CLOSING_STEPS;
  protected readonly unavailableStepText = UNAVAILABLE_STEP_TEXT;
  protected readonly dateFormat = DATE_FORMAT;
  protected readonly kindLabels = KIND_LABELS;
  protected readonly statusLabels = ENTRY_STATUS_LABELS;
  protected readonly summary = closingSummary;
  protected readonly blockerText = blockerText;

  ngOnInit(): void {
    let first = true;
    // «Ir a cerrar» lleva a otro mes en la misma ruta: cada cambio cancela el pedido anterior.
    this.route.paramMap
      .pipe(
        map((params) => params.get('period') ?? ''),
        tap((period) => {
          this.period.set(period);
          this.preview.set(null);
          this.error.set(null);
          this.loading.set(true);
        }),
        switchMap((period) => {
          if (!isPeriod(period)) {
            this.fail({ unavailable: true, message: 'La dirección no corresponde a un mes válido.' });
            return EMPTY;
          }
          return this.api.previewClosing(period).pipe(
            catchError((e: unknown) => {
              const notFound = e instanceof HttpErrorResponse && (e.status === 404 || e.status === 400);
              this.fail(
                notFound
                  ? {
                      unavailable: true,
                      message:
                        'Ese mes no existe en tu presupuesto: es anterior a tu período inicial o posterior al horizonte.',
                    }
                  : { unavailable: false, message: messageFor(e) },
              );
              return EMPTY;
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((preview) => {
        this.preview.set(preview);
        this.loading.set(false);
        if (!first) {
          // El enlace que se usó ya no existe: el foco va al título del mes nuevo.
          afterNextRender(() => this.title()?.nativeElement.focus(), { injector: this.injector });
        }
        first = false;
      });
  }

  private fail(error: PreviewError): void {
    this.error.set(error);
    this.loading.set(false);
  }
}
