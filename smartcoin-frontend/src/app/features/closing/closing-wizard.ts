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
import { AbstractControl, FormControl, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxChange, MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioChange, MatRadioModule } from '@angular/material/radio';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, EMPTY, map, Subject, switchMap } from 'rxjs';
import {
  CierreDeMesService,
  ClosingAccount,
  ClosingNewEntry,
  ClosingPendingEntry,
  ClosingPreview,
  ClosingPreviewRequest,
} from '../../api';
import { detailFor, messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { parseAmount } from '../../shared/amount';
import { CurrencyCode, MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { KIND_LABELS } from '../budget-items/budget-item-labels';
import { ENTRY_STATUS_LABELS } from '../budget/entry-table';
import { isPeriod } from '../budget/period-nav';
import {
  acknowledgeLabel,
  accountsByCurrency,
  BACK_STEP_LABEL,
  BACK_TO_BALANCES_LABEL,
  BALANCE_FORMAT_ERROR,
  balancesIntro,
  blockerText,
  CLOSING_STEPS,
  closeButtonLabel,
  closedMessage,
  closingSummary,
  CONFIRMATION_STEP_LABEL,
  consolidationText,
  confirmationHint,
  differenceText,
  effectText,
  irreversibleWarning,
  NEXT_STEP_LABEL,
  newEntryText,
  nextStepHint,
  REMOVED_ENTRIES_NOTICE,
  Resolution,
  RESOLUTION_OPTIONS,
  RESOLUTIONS_INTRO,
  sameEffectNote,
  stepForCloseError,
  UNAVAILABLE_STEP_TEXT,
} from './closing-text';

/** Por qué no se muestra la simulación: el período no existe para el usuario, o la API falló por otra causa. */
interface PreviewError {
  unavailable: boolean;
  message: string;
}

/**
 * Un pedido de simulación: el primero de cada mes va sin cuerpo; los siguientes, con las resoluciones elegidas y los
 * saldos reales cargados.
 */
interface PreviewRequest {
  period: string;
  body: ClosingPreviewRequest | null;
}

function amountFormat(control: AbstractControl): ValidationErrors | null {
  const value = (control.value as string).trim();
  return value === '' || parseAmount(value) !== null ? null : { amount: true };
}

/**
 * Asistente de cierre de mes (Épica 8), en `/cierre/:period`: tres pasos (pendientes, saldos y confirmación) sobre la
 * simulación del backend (`POST /api/periods/{period}/closing-preview`), que no modifica nada.
 *
 * HU-30 hace el diagnóstico del primer paso: si se puede cerrar y, si no, los motivos; las partidas pendientes con real
 * y pendiente, y las cuentas a conciliar con su saldo calculado al último día, agrupadas por moneda. HU-31 lo convierte
 * en «Pendientes»: si se puede cerrar, para cada pendiente se elige «Postergar saldo» o «Cerrar con lo registrado»; cada
 * elección vuelve a pedir la simulación y se muestra lo que va a pasar según el backend. Para avanzar a «Saldos» hay que
 * resolverlas todas. HU-32 habilita «Saldos»: para cada cuenta a conciliar se carga el saldo real y, con cada uno, se
 * vuelve a pedir la simulación para mostrar la diferencia y su efecto según el backend. Para avanzar a «Confirmación»
 * hay que cargarlos todos. HU-33 hace «Confirmación»: vuelve a pedir la simulación y muestra su resumen (qué se
 * consolida, qué se crea en el mes siguiente y la diferencia de cada cuenta), avisa que no se puede deshacer y pide una
 * casilla para habilitar «Cerrar <mes>» (D-45). El cierre (`POST /api/periods/{period}/close`) lleva al presupuesto del
 * mes cerrado con un aviso que ofrece el mes siguiente; ante un error, lo muestra y vuelve al paso que corresponde, sin
 * perder lo cargado. Las elecciones y los saldos viven en la pantalla (también al volver de paso): no se guarda nada
 * hasta el cierre. Todos los montos vienen del backend: acá no se suma ni se resta nada.
 */
@Component({
  selector: 'app-closing-wizard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatRadioModule,
    MoneyPipe,
    PeriodPipe,
    ReactiveFormsModule,
    RouterLink,
  ],
  templateUrl: './closing-wizard.html',
  styleUrl: './closing-wizard.scss',
})
export class ClosingWizard implements OnInit {
  private readonly api = inject(CierreDeMesService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly periodPipe = new PeriodPipe();
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly title = viewChild<ElementRef<HTMLElement>>('title');
  private readonly money = new MoneyPipe();
  private readonly requests = new Subject<PreviewRequest>();

  protected readonly period = signal<string | null>(null);
  protected readonly preview = signal<ClosingPreview | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<PreviewError | null>(null);
  protected readonly accountGroups = computed(() => accountsByCurrency(this.preview()?.accounts ?? []));
  protected readonly closed = computed(() => this.preview()?.status === 'CLOSED');

  /** Las resoluciones elegidas, por id de partida. Se pierden al cambiar de mes o recargar: no se guardan. */
  protected readonly choices = signal<ReadonlyMap<number, Resolution>>(new Map());
  /** El paso actual: 0 «Pendientes», 1 «Saldos», 2 «Confirmación». */
  protected readonly step = signal(0);
  /** Los saldos reales cargados y válidos, por id de cuenta. Se pierden al cambiar de mes o recargar. */
  protected readonly balances = signal<ReadonlyMap<number, number>>(new Map());
  /** El texto de cada campo de saldo real; sobrevive al volver a «Pendientes». */
  private readonly balanceControls = new Map<number, FormControl<string>>();
  /** Si hay una simulación con las últimas elecciones en camino. */
  protected readonly updating = signal(false);
  protected readonly updateError = signal<string | null>(null);
  /** Si alguna partida elegida dejó de estar pendiente en el período (D-43). */
  protected readonly removedEntries = signal(false);

  /** Se elige solo si el mes se puede cerrar: si no, el paso es el diagnóstico de HU-30 (D-43). */
  protected readonly canChoose = computed(() => {
    const current = this.preview();
    return current !== null && !this.closed() && current.preconditionsMet;
  });
  private readonly unresolvedCount = computed(() => this.preview()?.unresolvedEntryIds.length ?? 0);
  /** Todas resueltas según el backend, con la simulación al día con las elecciones. */
  private readonly allResolved = computed(
    () => this.canChoose() && !this.updating() && this.updateError() === null && this.unresolvedCount() === 0,
  );
  protected readonly canAdvance = computed(() => this.allResolved() && CLOSING_STEPS[1].available);
  protected readonly nextHint = computed(() =>
    nextStepHint(this.preview()?.pendingEntries.length ?? 0, this.unresolvedCount(), CLOSING_STEPS[1].available),
  );

  private readonly missingBalancesCount = computed(() => this.preview()?.missingRealBalanceAccountIds.length ?? 0);
  /** Todos los saldos cargados según el backend, con la simulación al día y sin campos con formato inválido. */
  private readonly allBalancesLoaded = computed(
    () => this.updateError() === null && !this.updating() && this.missingBalancesCount() === 0 && !this.hasInvalidText(),
  );
  protected readonly canConfirm = computed(() => this.allBalancesLoaded() && CLOSING_STEPS[2].available);
  protected readonly confirmHint = computed(() =>
    this.hasInvalidText()
      ? 'Revisá el saldo real con formato inválido.'
      : confirmationHint(this.preview()?.accounts.length ?? 0, this.missingBalancesCount(), CLOSING_STEPS[2].available),
  );
  /** Cambia cuando se escribe un texto de saldo inválido, para que la pista lo vea. */
  private readonly invalidTexts = signal<ReadonlySet<number>>(new Set());

  // Confirmación (HU-33).

  /** La casilla «Entiendo que … queda cerrado para siempre» (D-45); se desmarca al salir del paso. */
  protected readonly acknowledged = signal(false);
  /** Si el cierre está en camino. */
  protected readonly closing = signal(false);
  /** El error del último intento de cierre, que se muestra en el paso al que volvió el asistente. */
  protected readonly closeError = signal<string | null>(null);
  /** El mes con formato, para los textos: «agosto 2026». */
  protected readonly periodName = computed(() => this.periodPipe.transform(this.preview()?.period ?? this.period()));
  /** Todo listo según la última simulación: precondiciones, resoluciones y saldos, sin nada inválido ni en camino. */
  protected readonly readyToClose = computed(() => {
    const current = this.preview();
    return (
      current !== null &&
      this.canChoose() &&
      !this.updating() &&
      this.updateError() === null &&
      current.unresolvedEntryIds.length === 0 &&
      current.missingRealBalanceAccountIds.length === 0 &&
      current.invalidResolutions.length === 0 &&
      current.invalidBalances.length === 0 &&
      !this.hasInvalidText()
    );
  });
  protected readonly canClose = computed(() => this.readyToClose() && this.acknowledged() && !this.closing());

  protected readonly steps = CLOSING_STEPS;
  protected readonly unavailableStepText = UNAVAILABLE_STEP_TEXT;
  protected readonly dateFormat = DATE_FORMAT;
  protected readonly kindLabels = KIND_LABELS;
  protected readonly statusLabels = ENTRY_STATUS_LABELS;
  protected readonly summary = closingSummary;
  protected readonly blockerText = blockerText;
  protected readonly options = RESOLUTION_OPTIONS;
  protected readonly intro = RESOLUTIONS_INTRO;
  protected readonly nextLabel = NEXT_STEP_LABEL;
  protected readonly backLabel = BACK_STEP_LABEL;
  protected readonly confirmationLabel = CONFIRMATION_STEP_LABEL;
  protected readonly balanceFormatError = BALANCE_FORMAT_ERROR;
  protected readonly balancesIntro = balancesIntro;
  protected readonly removedNotice = REMOVED_ENTRIES_NOTICE;
  protected readonly sameEffectNote = sameEffectNote;
  protected readonly backToBalancesLabel = BACK_TO_BALANCES_LABEL;
  protected readonly irreversibleWarning = irreversibleWarning;
  protected readonly acknowledgeLabel = acknowledgeLabel;
  protected readonly closeButtonLabel = closeButtonLabel;

  ngOnInit(): void {
    let first = true;
    // Un pedido nuevo (otro mes u otra elección) cancela el anterior.
    this.requests
      .pipe(
        switchMap((request) => {
          const call = request.body
            ? this.api.previewClosing(request.period, request.body)
            : this.api.previewClosing(request.period);
          return call.pipe(
            map((preview) => ({ request, preview })),
            catchError((e: unknown) => {
              this.failed(request, e);
              return EMPTY;
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ request, preview }) => {
        if (request.period !== this.period()) {
          return;
        }
        if (request.body) {
          this.updated(preview);
          return;
        }
        this.preview.set(preview);
        this.loading.set(false);
        if (!first) {
          // El enlace que se usó ya no existe: el foco va al título del mes nuevo.
          afterNextRender(() => this.title()?.nativeElement.focus(), { injector: this.injector });
        }
        first = false;
      });

    // «Ir a cerrar» lleva a otro mes en la misma ruta.
    this.route.paramMap
      .pipe(
        map((params) => params.get('period') ?? ''),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((period) => {
        this.period.set(period);
        this.preview.set(null);
        this.error.set(null);
        this.loading.set(true);
        this.choices.set(new Map());
        this.step.set(0);
        this.balances.set(new Map());
        this.balanceControls.clear();
        this.invalidTexts.set(new Set());
        this.updating.set(false);
        this.updateError.set(null);
        this.removedEntries.set(false);
        this.acknowledged.set(false);
        this.closing.set(false);
        this.closeError.set(null);
        if (!isPeriod(period)) {
          this.fail({ unavailable: true, message: 'La dirección no corresponde a un mes válido.' });
          return;
        }
        this.requests.next({ period, body: null });
      });
  }

  protected choice(entry: ClosingPendingEntry): Resolution | null {
    return this.choices().get(entry.id) ?? null;
  }

  protected choose(entry: ClosingPendingEntry, event: MatRadioChange): void {
    this.choices.update((choices) => new Map(choices).set(entry.id, event.value as Resolution));
    this.simulate();
  }

  /** Elige la misma resolución para todas las pendientes; después se puede cambiar de a una. */
  protected chooseAll(resolution: Resolution): void {
    this.choices.set(new Map(this.preview()!.pendingEntries.map((entry) => [entry.id, resolution])));
    this.simulate();
  }

  /** Lo que va a pasar según el backend, o que se está calculando si la simulación no tiene la última elección. */
  protected effect(entry: ClosingPendingEntry): string | null {
    const chosen = this.choice(entry);
    if (chosen === null) {
      return null;
    }
    if (entry.resolution !== chosen) {
      return this.updateError() === null ? 'Calculando lo que va a pasar…' : null;
    }
    return effectText(entry, this.preview()!.period, (amount, currency: CurrencyCode) =>
      this.money.transform(amount, currency),
    );
  }

  private simulate(): void {
    this.updating.set(true);
    this.updateError.set(null);
    this.requests.next({ period: this.period()!, body: this.requestBody() });
  }

  // Paso «Saldos» (HU-32).

  protected goTo(step: number): void {
    this.step.set(step);
    this.acknowledged.set(false);
    this.closeError.set(null);
    if (step === 2) {
      // El resumen sale de una simulación nueva, con todo lo elegido y cargado.
      this.simulate();
    }
    afterNextRender(() => this.title()?.nativeElement.focus(), { injector: this.injector });
  }

  /** El campo de saldo real de una cuenta; se crea al mostrarlo y se conserva al volver a «Pendientes». */
  protected balanceControl(account: ClosingAccount): FormControl<string> {
    let control = this.balanceControls.get(account.id);
    if (!control) {
      control = new FormControl('', { nonNullable: true, validators: amountFormat });
      this.balanceControls.set(account.id, control);
    }
    return control;
  }

  /**
   * Se terminó de escribir el saldo real de una cuenta: con formato válido se vuelve a pedir la simulación, vacío lo
   * quita, y con formato inválido también (la cuenta cuenta como faltante) y el campo muestra el error.
   */
  protected balanceEntered(account: ClosingAccount): void {
    const control = this.balanceControl(account);
    control.markAsTouched();
    const text = control.value.trim();
    const amount = text === '' ? null : parseAmount(text);
    this.invalidTexts.update((invalid) => {
      const next = new Set(invalid);
      if (text !== '' && amount === null) {
        next.add(account.id);
      } else {
        next.delete(account.id);
      }
      return next;
    });
    const had = this.balances().get(account.id);
    if ((amount ?? undefined) === had) {
      return;
    }
    this.balances.update((balances) => {
      const next = new Map(balances);
      if (amount === null) {
        next.delete(account.id);
      } else {
        next.set(account.id, amount);
      }
      return next;
    });
    this.simulate();
  }

  // Paso «Confirmación» (HU-33).

  /** Las pendientes en el resumen: con qué resolución y monto se consolida cada una, según la simulación. */
  protected consolidation(entry: ClosingPendingEntry): string {
    return consolidationText(entry, (amount, currency: CurrencyCode) => this.money.transform(amount, currency));
  }

  protected newEntry(entry: ClosingNewEntry): string {
    return newEntryText(entry, (amount, currency: CurrencyCode) => this.money.transform(amount, currency));
  }

  protected acknowledge(event: MatCheckboxChange): void {
    this.acknowledged.set(event.checked);
  }

  /**
   * Cierra el mes con lo elegido y lo cargado. Al terminar lleva al presupuesto del mes cerrado, con un aviso que
   * ofrece ir al siguiente (D-45). Ante un error, lo muestra, vuelve a pedir la simulación y vuelve al paso que
   * corresponde, sin perder las elecciones ni los saldos.
   */
  protected close(): void {
    if (!this.canClose()) {
      return;
    }
    const period = this.period()!;
    const name = this.periodName();
    this.closing.set(true);
    this.closeError.set(null);
    this.api
      .closePeriod(period, this.requestBody())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.closing.set(false);
          const bar = this.snackBar.open(
            closedMessage(name),
            `Ir a ${this.periodPipe.transform(result.nextPeriod)}`,
            { duration: 10000 },
          );
          bar.onAction().subscribe(() => void this.router.navigate(['/presupuesto', result.nextPeriod]));
          void this.router.navigate(['/presupuesto', result.period]);
        },
        error: (e: unknown) => {
          this.closing.set(false);
          this.acknowledged.set(false);
          this.closeError.set(detailFor(e));
          const step = stepForCloseError(e);
          this.step.set(step);
          this.simulate();
          afterNextRender(() => this.title()?.nativeElement.focus(), { injector: this.injector });
        },
      });
  }

  private requestBody(): ClosingPreviewRequest {
    return {
      resolutions: [...this.choices()].map(([entryId, resolution]) => ({ entryId, resolution })),
      realBalances: [...this.balances()].map(([accountId, amount]) => ({ accountId, amount })),
    };
  }

  private hasInvalidText(): boolean {
    return this.invalidTexts().size > 0;
  }

  /** La diferencia que informa el backend, una vez que la simulación tiene el último saldo cargado. */
  protected shownDifference(account: ClosingAccount): number | null {
    return account.realBalance === this.balances().get(account.id) ? (account.difference ?? null) : null;
  }

  /** La diferencia y su efecto según el backend, o que se está calculando si la simulación no tiene el último saldo. */
  protected differenceEffect(account: ClosingAccount): string | null {
    const entered = this.balances().get(account.id);
    if (entered === undefined) {
      return null;
    }
    if (account.realBalance !== entered) {
      return this.updateError() === null ? 'Calculando la diferencia…' : null;
    }
    return differenceText(account, this.preview()!.period, (amount, currency: CurrencyCode) =>
      this.money.transform(amount, currency),
    );
  }

  /**
   * La simulación con las elecciones. Una elegida que ya no está pendiente en el período (consolidada en otra pestaña)
   * se quita de las elecciones y se avisa.
   */
  private updated(preview: ClosingPreview): void {
    const pending = new Set(preview.pendingEntries.map((entry) => entry.id));
    const kept = new Map([...this.choices()].filter(([id]) => pending.has(id)));
    if (kept.size !== this.choices().size || preview.invalidResolutions.length > 0) {
      this.removedEntries.set(true);
      this.choices.set(kept);
    }
    // Un saldo para una cuenta que ya no se concilia (D-44) se quita: su fila desapareció.
    const invalid = new Set(preview.invalidBalances.map((balance) => balance.accountId));
    if (invalid.size > 0) {
      this.balances.update((balances) => new Map([...balances].filter(([id]) => !invalid.has(id))));
    }
    this.preview.set(preview);
    this.updating.set(false);
    // Si dejó de estar todo resuelto (otra pestaña) o el mes ya no se puede cerrar, se vuelve a «Pendientes»; si
    // falta algún saldo, a «Saldos».
    if (this.step() > 0 && (preview.unresolvedEntryIds.length > 0 || !preview.preconditionsMet)) {
      this.step.set(0);
    } else if (this.step() > 1 && preview.missingRealBalanceAccountIds.length > 0) {
      this.step.set(1);
    }
  }

  private failed(request: PreviewRequest, e: unknown): void {
    if (request.period !== this.period()) {
      return;
    }
    if (request.body) {
      this.updating.set(false);
      this.updateError.set(messageFor(e));
      return;
    }
    const notFound = e instanceof HttpErrorResponse && (e.status === 404 || e.status === 400);
    this.fail(
      notFound
        ? {
            unavailable: true,
            message: 'Ese mes no existe en tu presupuesto: es anterior a tu período inicial o posterior al horizonte.',
          }
        : { unavailable: false, message: messageFor(e) },
    );
  }

  private fail(error: PreviewError): void {
    this.error.set(error);
    this.loading.set(false);
  }
}
